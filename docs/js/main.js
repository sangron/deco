document.addEventListener('DOMContentLoaded', () => {
    // --- Configuration ---
    const REPO_OWNER = 'sangron'; // Your GitHub username or organization
    const REPO_NAME = 'deco';   // The name of this repository

    // --- DOM Elements ---
    const messagesDiv = document.getElementById('messages');
    const loginSection = document.getElementById('login-section');
    const tokenInput = document.getElementById('github-token');
    const loginButton = document.getElementById('login-btn');
    const loadingIndicator = document.getElementById('loading-indicator');
    const readmeDisplayContainer = document.getElementById('readme-display-container');
    const readmeContentDiv = document.getElementById('readme-content');
    const interactiveAccordionContainer = document.getElementById('interactive-accordion-container');

    let octokit = null;

    // --- Core Functions ---

    /**
     * Displays a message in the messages div.
     */
    function displayMessage(text, type = 'info') {
        if (!messagesDiv) return;
        const typeClasses = {
            info: 'bg-sky-800 text-sky-100',
            success: 'bg-green-800 text-green-100',
            error: 'bg-red-800 text-red-100',
        };
        messagesDiv.className = `p-4 rounded-md mb-6 text-sm ${typeClasses[type]} || ''`;
        messagesDiv.textContent = text;
        messagesDiv.style.display = 'block';
    }

    /**
     * Fetches and displays the README.md content for read-only view.
     */
    async function showReadOnlyMode() {
        loadingIndicator.style.display = 'block';
        loginSection.style.display = 'none';
        try {
            const response = await fetch(`https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/readme`);
            if (!response.ok) throw new Error('Could not fetch README.');
            const data = await response.json();
            const readme = atob(data.content); // Decode base64 content
            readmeContentDiv.innerHTML = marked.parse(readme); // Render Markdown
            readmeDisplayContainer.style.display = 'block';
        } catch (error) {
            displayMessage('Failed to load repository information. Please try again later.', 'error');
            console.error(error);
        } finally {
            loadingIndicator.style.display = 'none';
        }
    }

    /**
     * Checks user permissions and shows the interactive accordion if authorized.
     */
    async function checkPermissionsAndInitialize() {
        if (!tokenInput.value) {
            displayMessage('Please enter a GitHub Personal Access Token.', 'error');
            return;
        }

        loadingIndicator.style.display = 'block';
        loginSection.style.display = 'none';

        octokit = new Octokit({ auth: tokenInput.value });

        try {
            const { data: user } = await octokit.request('GET /user');
            const username = user.login;

            const { data: permission } = await octokit.request('GET /repos/{owner}/{repo}/collaborators/{username}/permission', {
                owner: REPO_OWNER,
                repo: REPO_NAME,
                username: username,
            });

            const userPermission = permission.permission;
            if (userPermission === 'admin' || userPermission === 'write') {
                displayMessage(`Authenticated as ${username}. Welcome, maintainer!`, 'success');
                interactiveAccordionContainer.style.display = 'block';
                // Initial HTMX load for the first tab
                htmx.process(document.body); // Manually trigger HTMX processing
                const firstTab = document.querySelector('.tab-button');
                if(firstTab) htmx.ajax('GET', firstTab.getAttribute('hx-get'), '#tab-content');

            } else {
                displayMessage('You have read-only access.', 'info');
                await showReadOnlyMode();
            }
        } catch (error) {
            console.error('Authentication/Permission check failed:', error);
            displayMessage('Authentication failed. Please check your token and permissions.', 'error');
            loginSection.style.display = 'block'; // Show login again
        } finally {
            loadingIndicator.style.display = 'none';
        }
    }

    // --- Event Listeners ---
    loginButton.addEventListener('click', checkPermissionsAndInitialize);

    // Optional: Allow login with Enter key
    tokenInput.addEventListener('keyup', (event) => {
        if (event.key === 'Enter') {
            checkPermissionsAndInitialize();
        }
    });

    // Initial check: If the user wants to start without logging in
    // For this version, we require login first.
    // You could uncomment the next line to show read-only by default
    // showReadOnlyMode(); 
});