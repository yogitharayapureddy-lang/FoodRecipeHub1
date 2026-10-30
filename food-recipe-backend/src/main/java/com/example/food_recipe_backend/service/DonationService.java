
package com.example.food_recipe_backend.service;

import com.example.food_recipe_backend.dto.DonationRequest;
import com.example.food_recipe_backend.dto.VerifyDonationRequest;
import com.example.food_recipe_backend.entity.Donation;
import com.example.food_recipe_backend.entity.Recipe;
import com.example.food_recipe_backend.entity.User;
import com.example.food_recipe_backend.repository.DonationRepository;
import com.example.food_recipe_backend.repository.RecipeRepository;

import com.razorpay.*;
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

        if (request == null ||
                request.amount() == null ||
                request.recipeId() == null ||
                request.donorName() == null ||
                request.donorName().isBlank() ||
                request.donorName().length() > 100) {

            System.err.println("DONATION ERROR: Invalid donation details");

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid donation details"
            );
        }

        BigDecimal amount = request.amount();

        System.out.println("Recipe ID: " + request.recipeId());
        System.out.println("Donation amount: " + amount);

        if (amount.compareTo(new BigDecimal("10")) < 0 ||
                amount.compareTo(new BigDecimal("10000")) > 0 ||
                amount.stripTrailingZeros().scale() > 2) {

            System.err.println("DONATION ERROR: Invalid amount");

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Donation must be between Rs.10 and Rs.10000"
            );
        }

        // STEP 1: Find recipe
        Recipe recipe = recipeRepository
                .findById(request.recipeId())
                .orElseThrow(() -> {
                    System.err.println("DONATION ERROR: Recipe not found");

                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Recipe not found"
                    );
                });

        System.out.println("Recipe found: " + recipe.getName());

        // STEP 2: Find recipe creator
        User creator = recipe.getUser();

        if (creator == null) {

            System.err.println(
                    "DONATION ERROR: Recipe has no creator"
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Recipe creator not found"
            );
        }

        System.out.println("Creator ID: " + creator.getId());

        // STEP 3: Convert rupees to paise
        long amountPaise = amount
                .movePointRight(2)
                .longValueExact();

        System.out.println("Amount in paise: " + amountPaise);

        // STEP 4: Check Razorpay configuration
        boolean keyIdConfigured =
                keyId != null && !keyId.isBlank();

        boolean secretConfigured =
                keySecret != null && !keySecret.isBlank();

        System.out.println(
                "Razorpay Key ID configured: " + keyIdConfigured
        );

        System.out.println(
                "Razorpay Secret configured: " + secretConfigured
        );

        if (!keyIdConfigured || !secretConfigured) {

            System.err.println(
                    "DONATION ERROR: Razorpay credentials missing"
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment service is not configured"
            );
        }

        // STEP 5: Create Razorpay order
        String orderId;

        try {

            System.out.println("Creating Razorpay order...");

            RazorpayClient client =
                    new RazorpayClient(keyId, keySecret);

            JSONObject options = new JSONObject();

            options.put("amount", amountPaise);
            options.put("currency", "INR");
            options.put(
                    "receipt",
                    UUID.randomUUID().toString()
            );

            Order order = client.orders.create(options);

            orderId = order.get("id");

            System.out.println(
                    "Razorpay order created successfully"
            );

        } catch (RazorpayException e) {

            System.err.println(
                    "RAZORPAY ERROR: " + e.getMessage()
            );

            throw e;
        }

        // STEP 6: Save donation in PostgreSQL
        try {

            Donation donation = new Donation();

            donation.setDonorName(
                    request.donorName().trim()
            );

            donation.setAmountPaise(amountPaise);
            donation.setRazorpayOrderId(orderId);
            donation.setStatus("CREATED");

            donation.setRecipe(recipe);
            donation.setRecipientUser(creator);

            repository.save(donation);

            System.out.println(
                    "Donation saved successfully in database"
            );

        } catch (Exception e) {

            System.err.println(
                    "DATABASE ERROR while saving donation: "
                            + e.getMessage()
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

        if (request == null ||
                request.razorpayOrderId() == null ||
                request.razorpayPaymentId() == null ||
                request.razorpaySignature() == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing payment details"
            );
        }

        // STEP 1: Find donation
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

        // STEP 2: Prevent duplicate processing
        if ("PAID".equals(donation.getStatus())) {

            if (request.razorpayPaymentId()
                    .equals(donation.getRazorpayPaymentId())) {

                return Map.of("status", "PAID");
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order already paid"
            );
        }

        // STEP 3: Verify Razorpay signature
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

            System.err.println(
                    "PAYMENT ERROR: Invalid Razorpay signature"
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid payment signature"
            );
        }

        System.out.println("Payment signature verified");

        // STEP 4: Fetch payment details
        RazorpayClient client =
                new RazorpayClient(keyId, keySecret);

        Payment payment = client.payments.fetch(
                request.razorpayPaymentId()
        );

        String paymentOrderId =
                payment.get("order_id");

        String paymentStatus =
                payment.get("status");

        String currency =
                payment.get("currency");

        Number paidAmount =
                payment.get("amount");

        // STEP 5: Validate payment details
        if (!donation.getRazorpayOrderId()
                .equals(paymentOrderId) ||
                !"INR".equals(currency) ||
                paidAmount == null ||
                paidAmount.longValue() !=
                        donation.getAmountPaise()) {

            System.err.println(
                    "PAYMENT ERROR: Payment details mismatch"
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment details mismatch"
            );
        }

        if (!"captured".equals(paymentStatus)) {

            System.out.println(
                    "Payment is not captured yet"
            );

            return Map.of("status", "PENDING");
        }

        // STEP 6: Mark donation as PAID
        donation.setRazorpayPaymentId(
                request.razorpayPaymentId()
        );

        donation.setStatus("PAID");

        Donation savedDonation =
                repository.save(donation);

        System.out.println(
                "Donation payment saved successfully"
        );

        // STEP 7: Send email notification
        try {

            emailService.sendDonationReceivedEmail(
                    savedDonation
            );

            System.out.println(
                    "Donation notification email sent"
            );

        } catch (Exception e) {

            System.err.println(
                    "Payment succeeded but email failed: "
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
