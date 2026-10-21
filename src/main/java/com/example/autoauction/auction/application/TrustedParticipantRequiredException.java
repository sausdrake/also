package com.example.autoauction.auction.application;

/**
 * KYC-01: the Slice 1A controlled-beta trust gate refused a bid / Buy Now. Distinct from
 * {@code IdentityVerificationRequiredException}: the trusted status is granted by an administrator
 * separately and is never a consequence of a verified identity. Carries a stable code so the client can
 * tell the two refusals apart instead of asking a verified user to verify again.
 */
public class TrustedParticipantRequiredException extends SecurityException {

    public static final String CODE = "TRUSTED_PARTICIPANT_REQUIRED";

    public TrustedParticipantRequiredException() {
        super("Для участия в торгах нужен статус доверенного пользователя. Его выдаёт администратор: "
                + "подтверждение личности не даёт этот статус автоматически.");
    }

    public String getCode() {
        return CODE;
    }
}
