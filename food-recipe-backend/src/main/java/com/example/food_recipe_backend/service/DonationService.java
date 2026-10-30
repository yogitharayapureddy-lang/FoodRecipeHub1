
package com.example.food_recipe_backend.service;

import com.example.food_recipe_backend.dto.DonationRequest;
import com.example.food_recipe_backend.dto.VerifyDonationRequest;
import com.example.food_recipe_backend.entity.Donation;
import com.example.food_recipe_backend.entity.Recipe;
import com.example.food_recipe_backend.entity.User;
import com.example.food_recipe_backend.repository.DonationRepository;
import com.example.food_recipe_backend.repository.RecipeRepository;

import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class DonationService {

    private final DonationRepository repository;
    private final RecipeRepository recipeRepository;
    private final EmailService emailService;

    private final String keyId;
    private final String keySecret;

    public DonationService(
            DonationRepository repository,
            RecipeRepository recipeRepository,
            EmailService emailService,
            @Value("${razorpay.key.id}") String keyId,
            @Value("${razorpay.key.secret}") String keySecret) {

        this.repository = repository;
        this.recipeRepository = recipeRepository;
        this.emailService = emailService;
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    public Map<String, Object> createOrder(
            DonationRequest request) throws RazorpayException {

        System.out.println("========== DONATION SERVICE ==========");

        // STEP 1: Validate donation details
        if (request == null ||
                request.recipeId() == null ||
                request.amount() == null ||
                request.donorName() == null ||
                request.donorName().isBlank() ||
                request.donorName().trim().length() > 100) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid donation details"
            );
        }

        BigDecimal amount = request.amount();

        if (amount.compareTo(new BigDecimal("10")) < 0 ||
                amount.compareTo(new BigDecimal("10000")) > 0 ||
                amount.stripTrailingZeros().scale() > 2) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Donation must be between Rs.10 and Rs.10000"
            );
        }

        // STEP 2: Find recipe
        Recipe recipe = recipeRepository
                .findById(request.recipeId())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Recipe not found"
                        )
                );

        System.out.println("Recipe found: " + recipe.getName());

        // STEP 3: Creator is optional
        User creator = recipe.getUser();

        if (creator == null) {
            System.out.println(
                    "No recipe creator. Processing platform donation."
            );
        } else {
            System.out.println(
                    "Recipe creator ID: " + creator.getId()
            );
        }

        // STEP 4: Validate Razorpay configuration
        if (keyId == null || keyId.isBlank() ||
                keySecret == null || keySecret.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Razorpay credentials are missing"
            );
        }

        // STEP 5: Convert rupees to paise
        long amountPaise = amount
                .movePointRight(2)
                .longValueExact();

        System.out.println("Amount in paise: " + amountPaise);

        // STEP 6: Create Razorpay order
        RazorpayClient client =
                new RazorpayClient(keyId, keySecret);

        JSONObject options = new JSONObject();

        options.put("amount", amountPaise);
        options.put("currency", "INR");
        options.put("receipt", UUID.randomUUID().toString());

        Order order;

        try {
            order = client.orders.create(options);

            System.out.println(
                    "Razorpay order created successfully"
            );

        } catch (RazorpayException e) {

            System.err.println(
                    "Razorpay order creation failed: " + e.getMessage()
            );

            throw e;
        }

        String orderId = order.get("id");

        // STEP 7: Save donation
        Donation donation = new Donation();

        donation.setDonorName(request.donorName().trim());
        donation.setAmountPaise(amountPaise);
        donation.setRazorpayOrderId(orderId);
        donation.setStatus("CREATED");
        donation.setRecipe(recipe);

        // May be null for a platform donation
        donation.setRecipientUser(creator);

        try {
            repository.save(donation);

            System.out.println(
                    "Donation saved successfully in database"
            );

        } catch (Exception e) {

            System.err.println(
                    "DATABASE ERROR: " + e.getMessage()
            );

            throw e;
        }

        System.out.println(
                "========== DONATION ORDER SUCCESS =========="
        );

        return Map.of(
                "orderId", orderId,
                "amount", amountPaise,
                "currency", "INR",
                "keyId", keyId
        );
    }

    public Map<String, String> verifyPayment(
            VerifyDonationRequest request)
            throws RazorpayException {

        System.out.println("========== PAYMENT VERIFICATION ==========");

        // STEP 1: Validate request
        if (request == null ||
                request.razorpayOrderId() == null ||
                request.razorpayOrderId().isBlank() ||
                request.razorpayPaymentId() == null ||
                request.razorpayPaymentId().isBlank() ||
                request.razorpaySignature() == null ||
                request.razorpaySignature().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing payment details"
            );
        }

        // STEP 2: Find donation
        Donation donation = repository
                .findByRazorpayOrderId(
                        request.razorpayOrderId()
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Donation order not found"
                        )
                );

        // STEP 3: Prevent duplicate processing
        if ("PAID".equals(donation.getStatus())) {

            if (request.razorpayPaymentId().equals(
                    donation.getRazorpayPaymentId())) {

                return Map.of("status", "PAID");
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order already paid"
            );
        }

        // STEP 4: Verify Razorpay signature
        JSONObject verification = new JSONObject();

        verification.put(
                "razorpay_order_id",
                donation.getRazorpayOrderId()
        );

        verification.put(
                "razorpay_payment_id",
                request.razorpayPaymentId()
        );

        verification.put(
                "razorpay_signature",
                request.razorpaySignature()
        );

        boolean valid = Utils.verifyPaymentSignature(
                verification,
                keySecret
        );

        if (!valid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid payment signature"
            );
        }

        // STEP 5: Fetch payment from Razorpay
        RazorpayClient client =
                new RazorpayClient(keyId, keySecret);

        Payment payment = client.payments.fetch(
                request.razorpayPaymentId()
        );

        String paymentOrderId = payment.get("order_id");
        String paymentStatus = payment.get("status");
        String currency = payment.get("currency");
        Number paidAmount = payment.get("amount");

        // STEP 6: Validate payment details
        if (!donation.getRazorpayOrderId().equals(paymentOrderId) ||
                !"INR".equals(currency) ||
                paidAmount == null ||
                paidAmount.longValue() !=
                        donation.getAmountPaise()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment details mismatch"
            );
        }

        // STEP 7: Check payment capture
        if (!"captured".equals(paymentStatus)) {

            System.out.println(
                    "Payment not captured. Status: " + paymentStatus
            );

            return Map.of("status", "PENDING");
        }

        // STEP 8: Save successful payment
        donation.setRazorpayPaymentId(
                request.razorpayPaymentId()
        );

        donation.setStatus("PAID");

        Donation savedDonation = repository.save(donation);

        System.out.println(
                "Donation marked as PAID successfully"
        );

        // STEP 9: Email is optional
        try {

            if (savedDonation.getRecipientUser() != null) {

                emailService.sendDonationReceivedEmail(
                        savedDonation
                );

                System.out.println(
                        "Donation notification email sent"
                );

            } else {

                System.out.println(
                        "Platform donation: creator email skipped"
                );
            }

        } catch (Exception e) {

            System.err.println(
                    "Payment verified, but email failed: "
                            + e.getMessage()
            );
        }

        System.out.println(
                "========== PAYMENT VERIFIED =========="
        );

        return Map.of(
                "status", "PAID",
                "message", "Donation verified successfully"
        );
    }
}
