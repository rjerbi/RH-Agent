const chatContainer = document.getElementById("chatContainer");
const messageInput = document.getElementById("messageInput");
const sendBtn = document.getElementById("sendBtn");
const newChatBtn = document.getElementById("newChatBtn");
const deleteChatBtn = document.getElementById("deleteChatBtn");
const chatHomeBtn = document.getElementById("chatHomeBtn");
const chatForm = document.getElementById("chatForm");
const conversationKey = "rhRecruitmentConversationId";
const welcomeScreenTemplate = document.querySelector(".welcome-screen").cloneNode(true);

function generateConversationId() {
    return "chat-" + Date.now();
}

let conversationId = localStorage.getItem(conversationKey);

if (!conversationId) {
    conversationId = generateConversationId();
    localStorage.setItem(conversationKey, conversationId);
}

function getHistoryKey() {
    return "rhRecruitmentMessages-" + conversationId;
}

function startNewConversation() {
    conversationId = generateConversationId();
    localStorage.setItem(conversationKey, conversationId);
}

function bindPromptChips(container) {
    container.querySelectorAll(".prompt-chip").forEach(function(button) {
        button.addEventListener("click", function() {
            messageInput.value = button.dataset.prompt;
            sendMessage();
        });
    });
}

function showWelcomeScreen() {
    chatContainer.replaceChildren(welcomeScreenTemplate.cloneNode(true));
    bindPromptChips(chatContainer);
}

function addMessage(message, sender, save = true) {
    const welcomeScreen = document.querySelector(".welcome-screen");

    if (save && welcomeScreen) {
        welcomeScreen.remove();
    }

    const messageDiv = document.createElement("div");
    messageDiv.className = "message " + sender;

    const avatar = document.createElement("div");
    avatar.className = "message-avatar";
    avatar.textContent = sender === "user" ? "You" : "RH";

    const body = document.createElement("div");
    body.className = "message-body";

    const meta = document.createElement("div");
    meta.className = "message-meta";

    const name = document.createElement("strong");
    name.textContent = sender === "user" ? "You" : "RH Assistant";

    const time = document.createElement("span");
    time.textContent = new Date().toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit"
    });

    const content = document.createElement("div");
    content.className = "message-content";
    content.textContent = message;

    meta.append(name, time);
    body.append(meta, content);
    messageDiv.append(avatar, body);
    chatContainer.appendChild(messageDiv);

    if (save) {
        const messages = JSON.parse(localStorage.getItem(getHistoryKey()) || "[]");
        messages.push({ message, sender });
        localStorage.setItem(getHistoryKey(), JSON.stringify(messages));
    }

    chatContainer.scrollTop = chatContainer.scrollHeight;
}

function restoreMessages() {
    const messages = JSON.parse(localStorage.getItem(getHistoryKey()) || "[]");

    if (!Array.isArray(messages) || messages.length === 0) {
        return;
    }

    chatContainer.replaceChildren();
    messages.forEach(item => addMessage(item.message, item.sender, false));
}

function createLoadingMessage() {
    const messageDiv = document.createElement("div");
    messageDiv.className = "message assistant";

    const avatar = document.createElement("div");
    avatar.className = "message-avatar";
    avatar.textContent = "RH";

    const body = document.createElement("div");
    body.className = "message-body";

    const content = document.createElement("div");
    content.className = "message-content loading";
    content.textContent = "Thinking";

    body.appendChild(content);
    messageDiv.append(avatar, body);
    chatContainer.appendChild(messageDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;

    return messageDiv;
}

async function sendMessage() {
    const message = messageInput.value.trim();

    if (!message || sendBtn.disabled) {
        return;
    }

    addMessage(message, "user");
    messageInput.value = "";
    messageInput.style.height = "auto";

    sendBtn.disabled = true;
    newChatBtn.disabled = true;
    deleteChatBtn.disabled = true;
    chatHomeBtn.disabled = true;
    const loadingMessage = createLoadingMessage();

    try {
        const url = `/api/chat?message=${encodeURIComponent(message)}&conversationId=${encodeURIComponent(conversationId)}`;
        const response = await fetch(url);

        if (!response.ok) {
            throw new Error(`Request failed with status ${response.status}`);
        }

        const answer = await response.text();
        loadingMessage.remove();
        addMessage(answer || "The assistant returned an empty response.", "assistant");

    } catch (error) {
        loadingMessage.remove();
        addMessage("I couldn't reach the assistant. Check that the agent client is running and try again.", "assistant");
        console.error("Chat request failed:", error);
    } finally {
        sendBtn.disabled = false;
        newChatBtn.disabled = false;
        deleteChatBtn.disabled = false;
        chatHomeBtn.disabled = false;
        messageInput.focus();
    }
}

chatForm.addEventListener("submit", function(event) {
    event.preventDefault();
    sendMessage();
});

messageInput.addEventListener("keydown", function(event) {
    if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
});

messageInput.addEventListener("input", function() {
    messageInput.style.height = "auto";
    messageInput.style.height = Math.min(messageInput.scrollHeight, 120) + "px";
});

bindPromptChips(document);

newChatBtn.addEventListener("click", function() {
    startNewConversation();
    chatContainer.replaceChildren();
    addMessage("New conversation started. What would you like to find?", "assistant");
    messageInput.focus();
});

chatHomeBtn.addEventListener("click", function() {
    startNewConversation();
    showWelcomeScreen();
    messageInput.focus();
});

deleteChatBtn.addEventListener("click", async function() {
    if (!window.confirm("Delete this conversation from chat memory?")) {
        return;
    }

    deleteChatBtn.disabled = true;
    newChatBtn.disabled = true;
    sendBtn.disabled = true;
    chatHomeBtn.disabled = true;

    try {
        const response = await fetch(
            `/api/chat/${encodeURIComponent(conversationId)}`,
            { method: "DELETE" }
        );

        if (!response.ok) {
            throw new Error(`Request failed with status ${response.status}`);
        }

        localStorage.removeItem(getHistoryKey());
        conversationId = generateConversationId();
        localStorage.setItem(conversationKey, conversationId);
        chatContainer.replaceChildren();
        addMessage("Conversation deleted.", "assistant", false);
    } catch (error) {
        addMessage("I couldn't delete this conversation. Please try again.", "assistant", false);
        console.error("Conversation deletion failed:", error);
    } finally {
        deleteChatBtn.disabled = false;
        newChatBtn.disabled = false;
        sendBtn.disabled = false;
        chatHomeBtn.disabled = false;
    }
});

restoreMessages();