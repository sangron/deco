// File: src/lib.rs (for the Individual DeCo Token & Governance Blueprint Contract)

use near_sdk::borsh::{self, BorshDeserialize, BorshSerialize};
use near_sdk::collections::{LazyOption, LookupMap, UnorderedSet};
use near_sdk::json_types::{U128};
use near_sdk::{env, near_bindgen, AccountId, Balance, Promise, Gas};

// Standard FT (Fungible Token) interface for NEAR.
// We'll use a simplified version, but a real implementation would use near-contract-standards.
// For full FT standard: https://docs.near.org/standards/tokens/ft
const FT_STORAGE_DEPOSIT: Balance = 1_250_000_000_000_000_000_000_000; // 1.25 NEAR (for storage of FT accounts)
const BASE_GAS: Gas = Gas(5_000_000_000_000); // 5 TGas base gas for cross-contract calls

// --- Contract State ---
#[near_bindgen]
#[derive(BorshDeserialize, BorshSerialize)]
pub struct IndividualDeCoContract {
    // Basic FT Metadata (from NEP-148)
    pub token_name: String,
    pub token_symbol: String,
    pub token_decimals: u8,
    pub total_supply: U128, // Total supply of this DeCo's token

    // Balances of users for this DeCo's token
    pub balances: LookupMap<AccountId, Balance>,

    // The NEAR account ID that owns this DeCo instance (usually the DeCo's main account)
    pub deco_owner_account_id: AccountId,

    // List of active members (can be used for governance weighting, special access)
    // The oracle can update this based on GitHub roles (maintainers, contributors)
    pub active_members: UnorderedSet<AccountId>,

    // Reference to the DeCo Zero Service Contract (for potential future interactions)
    pub deco_zero_service_contract_id: AccountId,

    // Optional: Storage for the current "values.csv" rules (hashed or stored directly for audit)
    // Could store hash of the CSV or a parsed version of it.
    pub current_values_csv_hash: LazyOption<String>, 
}

// --- Fungible Token Metadata (NEP-148) for view methods ---
#[derive(BorshDeserialize, BorshSerialize, Debug, PartialEq)]
#[serde(crate = "near_sdk::serde")]
pub struct FungibleTokenMetadata {
    pub spec: String, // Should be "ft-1.0.0"
    pub name: String,
    pub symbol: String,
    pub icon: Option<String>,
    pub reference: Option<String>,
    pub reference_hash: Option<near_sdk::Base64VecU8>,
    pub decimals: u8,
}

// --- Contract Initialization ---
impl Default for IndividualDeCoContract {
    fn default() -> Self {
        panic!("Contract should be initialized using `new` method.")
    }
}

#[near_bindgen]
impl IndividualDeCoContract {
    // Constructor for an individual DeCo contract instance
    // This is called when deploying the contract to initialize its state.
    #[init]
    pub fn new(
        deco_owner_account_id: AccountId,
        token_name: String,
        token_symbol: String,
        token_decimals: u8,
        deco_zero_service_contract_id: AccountId,
    ) -> Self {
        assert!(!env::state_exists(), "Contract is already initialized"); // Prevent re-initialization
        assert!(token_decimals <= 24, "Decimals must be 24 or less for NEAR tokens."); // Standard NEAR decimals

        Self {
            token_name,
            token_symbol,
            token_decimals,
            total_supply: U128(0), // Initial supply is 0, minted as contributions accrue
            balances: LookupMap::new(b"b".to_vec()),
            deco_owner_account_id,
            active_members: UnorderedSet::new(b"m".to_vec()),
            deco_zero_service_contract_id,
            current_values_csv_hash: LazyOption::new(b"h".to_vec(), None),
        }
    }

    // --- Token Management (called by Oracle / Governance) ---

    // Mints new tokens to a specific account.
    // This function should typically only be callable by the oracle or through a governance vote.
    // We will enforce that it can only be called by the `deco_owner_account_id` or a whitelisted oracle.
    #[private] // Marks this function as callable only by the contract itself (if setup as callback)
    pub fn mint(&mut self, account_id: AccountId, amount: U128) {
        // In a real DeCo, this would be restricted to the oracle's account or a governance approved call.
        // For now, we'll keep it simple to illustrate.
        // A more robust solution would check `env::predecessor_account_id()` against a whitelist of oracles
        // or ensure it's part of a cross-contract call from a trusted governance contract.
        
        // This is a simplified check: assumes only the deco_owner_account_id or a trusted oracle calls it.
        // For production, strengthen this with `env::predecessor_account_id()` checks.
        // assert_eq!(env::predecessor_account_id(), self.deco_owner_account_id, "Only the DeCo owner can mint tokens (or authorized oracle).");

        let current_balance = self.internal_unwrap_balance_of(&account_id);
        let new_balance = current_balance + amount.0;
        self.balances.insert(&account_id, &new_balance);
        self.total_supply = U128(self.total_supply.0 + amount.0);
        
        env::log_str(&format!("MINT: {} tokens to {}", amount.0, account_id));
        // Emit FT mint event for indexers if using the FT standard
    }

    // Burns tokens from a specific account.
    // This would be called when a user converts their DeCo tokens to other cryptocurrencies.
    pub fn burn(&mut self, account_id: AccountId, amount: U128) {
        assert!(amount.0 > 0, "Burn amount must be positive.");
        // Only the account owner or authorized entity can burn from their balance.
        assert_eq!(env::predecessor_account_id(), account_id, "Only the account owner can burn their tokens.");

        let current_balance = self.internal_unwrap_balance_of(&account_id);
        assert!(current_balance >= amount.0, "Insufficient balance to burn.");

        let new_balance = current_balance - amount.0;
        self.balances.insert(&account_id, &new_balance);
        self.total_supply = U128(self.total_supply.0 - amount.0);

        env::log_str(&format!("BURN: {} tokens from {}", amount.0, account_id));
        // Emit FT burn event for indexers if using the FT standard
    }

    // --- Member Management (could be updated by Oracle or Governance) ---
    pub fn add_active_member(&mut self, account_id: AccountId) {
        // This function should be restricted to governance or trusted oracle.
        // For simplicity:
        assert_eq!(env::predecessor_account_id(), self.deco_owner_account_id, "Only DeCo owner can add members.");
        self.active_members.insert(&account_id);
    }

    pub fn remove_active_member(&mut self, account_id: AccountId) {
        // This function should be restricted to governance or trusted oracle.
        assert_eq!(env::predecessor_account_id(), self.deco_owner_account_id, "Only DeCo owner can remove members.");
        self.active_members.remove(&account_id);
    }

    // Updates the hash of the values.csv file.
    // This would be called by the oracle after a governance-approved change to values.csv
    pub fn set_values_csv_hash(&mut self, new_hash: String) {
        // This needs strong governance/oracle control.
        assert_eq!(env::predecessor_account_id(), self.deco_owner_account_id, "Only DeCo owner can update values hash.");
        self.current_values_csv_hash = LazyOption::new(b"h".to_vec(), Some(&new_hash));
    }


    // --- View Methods (read-only) ---

    pub fn ft_total_supply(&self) -> U128 {
        self.total_supply
    }

    pub fn ft_balance_of(&self, account_id: AccountId) -> U128 {
        U128(self.balances.get(&account_id).unwrap_or(0))
    }

    pub fn get_deco_owner(&self) -> AccountId {
        self.deco_owner_account_id.clone()
    }

    pub fn is_active_member(&self, account_id: AccountId) -> bool {
        self.active_members.contains(&account_id)
    }

    pub fn get_values_csv_hash(&self) -> Option<String> {
        self.current_values_csv_hash.get()
    }

    // Returns FT metadata (NEP-148)
    pub fn ft_metadata(&self) -> FungibleTokenMetadata {
        FungibleTokenMetadata {
            spec: "ft-1.0.0".to_string(),
            name: self.token_name.clone(),
            symbol: self.token_symbol.clone(),
            icon: None, // Or a URL to an icon
            reference: None,
            reference_hash: None,
            decimals: self.token_decimals,
        }
    }

    // --- Internal Helpers ---
    fn internal_unwrap_balance_of(&self, account_id: &AccountId) -> Balance {
        self.balances.get(account_id).unwrap_or(0)
    }
}

