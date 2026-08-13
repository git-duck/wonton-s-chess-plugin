package com.wonton.chess;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

/**
 * Vault-backed BalanceService. Only loaded when Vault is available;
 * never referenced directly from ChessPlugin so the plugin works without Vault.
 */
public class VaultBalanceService implements BalanceService {
    private final Economy economy;

    public VaultBalanceService(Object provider) {
        this.economy = (Economy) provider;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    @Override
    public void withdraw(OfflinePlayer player, double amount) {
        economy.withdrawPlayer(player, amount);
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        economy.depositPlayer(player, amount);
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }

    @Override
    public String name() {
        return economy.getName();
    }
}
