package dev.edyiaeonian.fxledger.account;

/** Who an account belongs to; only customer accounts must never go negative. */
public enum AccountType {
    CUSTOMER,
    FEE_REVENUE,
    FX_POSITION,
    FUNDING
}
