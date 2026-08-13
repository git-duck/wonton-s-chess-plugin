package com.wonton.chess;

import org.bukkit.OfflinePlayer;

/**
 * Economy abstraction so the plugin loads fine without Vault installed.
 * The Vault implementation is only loaded reflectively when Vault is present.
 */
public interface BalanceService {
    boolean has(OfflinePlayer player, double amount);

    double balance(OfflinePlayer player);

    void withdraw(OfflinePlayer player, double amount);

    void deposit(OfflinePlayer player, double amount);

    String format(double amount);

    String name();
}
