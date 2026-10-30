
package com.example.food_recipe_backend.service;

import com.example.food_recipe_backend.entity.Donation;
import com.example.food_recipe_backend.entity.Recipe;
import com.example.food_recipe_backend.entity.User;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${recipehub.mail.from}") String fromEmail) {

        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    public void sendDonationReceivedEmail(Donation donation) {

        // STEP 1: Get donation details
        Recipe recipe = donation.getRecipe();
        User creator = donation.getRecipientUser();

        String recipeName =
                recipe != null && recipe.getName() != null
                        ? recipe.getName()
                        : "RecipeHub Recipe";

        String donorName =
                donation.getDonorName() != null
                        ? donation.getDonorName()
                        : "Anonymous";

        BigDecimal amount = BigDecimal.valueOf(
                donation.getAmountPaise(), 2
        );

        // STEP 2: Prepare payment notification
        String subject =
                "RecipeHub - Donation Payment Successful!";

        String body =
                "Hello RecipeHub Team,\n\n" +

                "A donation payment has been successfully " +
                "received on RecipeHub.\n\n" +

                "PAYMENT DETAILS\n" +
                "----------------------------------\n" +

                "Recipe Name: " + recipeName + "\n" +

                "Donor Name: " + donorName + "\n" +

                "Amount: Rs. " +
                amount.toPlainString() + "\n" +

                "Payment Status: PAID\n\n" +

                "Razorpay Order ID: " +
                donation.getRazorpayOrderId() + "\n" +

                "Razorpay Payment ID: " +
                donation.getRazorpayPaymentId() + "\n\n" +

                "The donation has been processed through " +
                "RecipeHub's Razorpay merchant account.\n\n" +

                "Thank you!\n\n" +

                "Best Regards,\n" +
                "RecipeHub Team";

        // STEP 3: Send notification to RecipeHub Gmail
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(fromEmail);
        message.setTo(fromEmail);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);

        System.out.println(
                "Payment success notification sent to: " +
                fromEmail
        );

        // STEP 4: Notify recipe creator if available
        if (creator != null &&
                creator.getEmail() != null &&
                !creator.getEmail().isBlank() &&
                !creator.getEmail().equalsIgnoreCase(fromEmail)) {

            try {

                String creatorName =
                        creator.getName() != null &&
                        !creator.getName().isBlank()
                                ? creator.getName()
                                : "Recipe Creator";

                String creatorBody =
                        "Hello " + creatorName + ",\n\n" +

                        "Someone has supported your recipe " +
                        "on RecipeHub!\n\n" +

                        "Recipe: " + recipeName + "\n" +

                        "Donor: " + donorName + "\n" +

                        "Amount: Rs. " +
                        amount.toPlainString() + "\n" +

                        "Payment Status: PAID\n\n" +

                        "Note: Donations are processed " +
                        "through RecipeHub's Razorpay " +
                        "merchant account.\n\n" +

                        "Thank you for sharing your recipes!\n\n" +

                        "Best Regards,\n" +
                        "RecipeHub Team";

                SimpleMailMessage creatorMessage =
                        new SimpleMailMessage();

                creatorMessage.setFrom(fromEmail);
                creatorMessage.setTo(creator.getEmail());
                creatorMessage.setSubject(
                        "RecipeHub - Your Recipe Received a Donation!"
                );
                creatorMessage.setText(creatorBody);

                mailSender.send(creatorMessage);

                System.out.println(
                        "Creator donation notification sent successfully"
                );

            } catch (Exception e) {

                System.err.println(
                        "Creator email notification failed: " +
                        e.getMessage()
                );
            }
        }
    }
}
