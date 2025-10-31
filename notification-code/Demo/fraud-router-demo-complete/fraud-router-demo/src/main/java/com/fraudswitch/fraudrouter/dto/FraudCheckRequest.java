package com.fraudswitch.fraudrouter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fraud Check Request DTO
 * Represents incoming transaction data from ETS (External Transaction Service)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCheckRequest {
    
    @NotBlank(message = "Transaction ID is required")
    @JsonProperty("transaction_id")
    private String transactionId;
    
    @NotBlank(message = "Merchant ID is required")
    @JsonProperty("merchant_id")
    private String merchantId;
    
    @NotNull(message = "Amount is required")
    private BigDecimal amount;
    
    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be 3-letter ISO code")
    private String currency;
    
    @NotBlank(message = "Transaction type is required")
    @JsonProperty("transaction_type")
    private String transactionType; // auth, capture, refund
    
    @JsonProperty("card_bin")
    private String cardBin; // First 6 digits
    
    @JsonProperty("card_last_four")
    private String cardLastFour;
    
    @JsonProperty("payment_method")
    private String paymentMethod; // stripe, adyen, braintree
    
    @JsonProperty("customer_id")
    private String customerId;
    
    @JsonProperty("customer_email")
    private String customerEmail;
    
    @JsonProperty("ip_address")
    private String ipAddress;
    
    @JsonProperty("device_id")
    private String deviceId;
    
    @JsonProperty("billing_country")
    private String billingCountry;
    
    @JsonProperty("shipping_country")
    private String shippingCountry;
    
    @Builder.Default
    private Instant timestamp = Instant.now();
    
    // Metadata
    @JsonProperty("async_mode")
    @Builder.Default
    private Boolean asyncMode = false;
    
    @JsonProperty("provider_preference")
    private String providerPreference; // ravelin, signifyd
}
