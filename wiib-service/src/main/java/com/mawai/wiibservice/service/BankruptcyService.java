package com.mawai.wiibservice.service;

import java.time.LocalDate;

public interface BankruptcyService {

    void checkAndLiquidateAll();

    void resetBankruptUsers(LocalDate today);

    LocalDate nextTradingDay(LocalDate d);
}

