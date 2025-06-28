// File: src/lib.rs (for the DeCo Zero Smart Contract)

use near_sdk::borsh::{self, BorshDeserialize, BorshSerialize};
use near_sdk::collections::LookupMap;
use near_sdk::json_types::{U128};
use near_sdk::{env, near_bindgen, AccountId, Balance, Promise};

// --- Smart Contract State ---
// Define the contract state struct
#[near_bindgen]
#[derive(BorshDeserialize, BorshSerialize)]
pub struct DecoZeroContract {
    // Owner of the DeCo Zero contract (can manage fees, etc.)
    pub owner: AccountId,
    // Minimum NEAR required to request AI document generation services
    pub service_fee_min_near: Balance,
    // A map to keep track of service requests (optional, for auditing/retries)
    // Key: GitHub Repo URL, Value: Last recorded service request data
    pub service_requests: LookupMap<String, ServiceRequest>,
    // Optional: Total services provided, total fees collected
    pub total_services_provided: u64,
    pub total_fees_collected: Balance,
}

// Struct to represent a service request, emitted as an event
#[derive(BorshDeserialize, BorshSerialize, Debug)]
pub struct ServiceRequest {
    pub requester_id: AccountId,
    pub github_repo_url: String,
    pub selected_areas: Vec<String>,
    pub timestamp: u64,
    pub transaction_id: String, // Unique ID for the NEAR transaction that initiated this request
}

// --- Contract Initialization ---
impl Default for DecoZeroContract {
    fn default() -> Self {
        panic!("Contract should be initialized using `new` method.")
    }
}

#[near_bindgen]
impl DecoZeroContract {
    // Constructor for the contract
    // This is called when deploying the contract to initialize its state.
    #[init]
    pub fn new(owner_id: AccountId, initial_service_fee_min_near: U128) -> Self {
        assert!(!env::state_exists(), "Already initialized"); // Ensure contract is not re-initialized
        Self {
            owner: owner_id,
            service_fee_min_near: initial_service_fee_min_near.0,
            service_requests: LookupMap::new(b"s".to_vec()), // 's' is a unique prefix for storage
            total_services_provided: 0,
            total_fees_collected: 0,
        }
    }

    // --- Public Service Method ---
    // This method is called by a new DeCo (e.g., Juan's DeCo) to request AI document generation.
    // It expects a payment (attached deposit) and relevant information.
    #[payable] // Marks this function as payable, allowing it to receive NEAR tokens
    pub fn request_document_generation(
        &mut self,
        github_repo_url: String,
        selected_areas: Vec<String>,
    ) {
        // Ensure that the attached deposit is sufficient for the service fee
        assert!(
            env::attached_deposit() >= self.service_fee_min_near,
            "Insufficient deposit. Minimum required: {} NEAR",
            self.service_fee_min_near as f64 / 1_000_000_000_000_000_000_000_000.0 // Convert yoctoNEAR to NEAR for message
        );

        let requester_id = env::predecessor_account_id();
        let current_timestamp = env::block_timestamp(); // Unix timestamp in nanoseconds
        let current_transaction_id = env::current_account_id().to_string() + &current_timestamp.to_string(); // Simple unique ID

        // Store the service request data
        let request = ServiceRequest {
            requester_id: requester_id.clone(),
            github_repo_url: github_repo_url.clone(),
            selected_areas: selected_areas.clone(),
            timestamp: current_timestamp,
            transaction_id: current_transaction_id.clone(),
        };
        self.service_requests.insert(&github_repo_url, &request);

        // Update contract metrics
        self.total_services_provided += 1;
        self.total_fees_collected += env::attached_deposit();

        // Emit an event (log) that the off-chain oracle can listen for.
        // The oracle will then trigger the AI generation and GitHub commits.
        env::log_str(&format!(
            "EVENT_SERVICE_REQUESTED: {{ \"requester_id\": \"{}\", \"github_repo_url\": \"{}\", \"selected_areas\": {:?}, \"timestamp\": {}, \"transaction_id\": \"{}\", \"fee_paid\": \"{}\" }}",
            requester_id,
            github_repo_url,
            selected_areas,
            current_timestamp,
            current_transaction_id,
            env::attached_deposit().to_string()
        ));

        // You could also add a Promise to transfer part of the fees to an operational wallet if needed
        // Promise::new(self.owner.clone()).transfer(env::attached_deposit());
    }

    // --- Owner-Only Methods (for contract management) ---

    // Allows the owner to withdraw collected fees.
    pub fn owner_withdraw_fees(&mut self, amount: U128, to_account: AccountId) -> Promise {
        assert_eq!(env::predecessor_account_id(), self.owner, "Only the owner can withdraw fees.");
        assert!(self.total_fees_collected >= amount.0, "Insufficient collected fees to withdraw this amount.");

        self.total_fees_collected -= amount.0;
        Promise::new(to_account).transfer(amount.0)
    }

    // Allows the owner to change the service fee.
    pub fn owner_set_service_fee(&mut self, new_fee: U128) {
        assert_eq!(env::predecessor_account_id(), self.owner, "Only the owner can set the service fee.");
        self.service_fee_min_near = new_fee.0;
        env::log_str(&format!("Service fee updated to: {}", new_fee.0));
    }

    // --- View Methods (read-only, no transaction needed) ---

    // Returns the current service fee.
    pub fn get_service_fee(&self) -> U128 {
        U128(self.service_fee_min_near)
    }

    // Returns the total number of services provided.
    pub fn get_total_services_provided(&self) -> u64 {
        self.total_services_provided
    }

    // Returns the total fees collected by the contract.
    pub fn get_total_fees_collected(&self) -> U128 {
        U128(self.total_fees_collected)
    }

    // Retrieves a specific service request by GitHub repository URL.
    pub fn get_service_request_by_repo_url(&self, github_repo_url: String) -> Option<ServiceRequest> {
        self.service_requests.get(&github_repo_url)
    }
}

