// Minimal Forvum web chat: one WebSocket to /ws/chat, one conversation per browser tab.
// Each user message is sent as a raw text frame; each rendered AgentEvent comes back as a text frame.
(() => {
    const log = document.getElementById("log");
    const form = document.getElementById("composer");
    const input = document.getElementById("input");
    const send = document.getElementById("send");

    const append = (text, role) => {
        const el = document.createElement("div");
        el.className = `msg ${role}`;
        el.textContent = text;
        log.appendChild(el);
        log.scrollTop = log.scrollHeight;
    };

    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const socket = new WebSocket(`${scheme}://${location.host}/ws/chat`);

    const setEnabled = (on) => {
        input.disabled = !on;
        send.disabled = !on;
    };
    setEnabled(false);

    socket.addEventListener("open", () => {
        append("Connected.", "system");
        setEnabled(true);
        input.focus();
    });
    socket.addEventListener("message", (event) => append(event.data, "assistant"));
    socket.addEventListener("close", () => {
        append("Disconnected.", "system");
        setEnabled(false);
    });
    socket.addEventListener("error", () => append("Connection error.", "system"));

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        const text = input.value.trim();
        if (!text || socket.readyState !== WebSocket.OPEN) {
            return;
        }
        append(text, "user");
        socket.send(text);
        input.value = "";
    });
})();
