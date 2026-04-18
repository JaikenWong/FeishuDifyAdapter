const loginForm = document.getElementById('loginForm');
const loginMessage = document.getElementById('loginMessage');

fetch('/api/auth/me').then(resp => {
    if (resp.ok) {
        window.location.href = '/index.html';
    }
});

loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    loginMessage.textContent = '登录中...';
    const formData = new FormData(loginForm);
    const payload = Object.fromEntries(formData.entries());

    const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });

    if (response.ok) {
        window.location.href = '/index.html';
        return;
    }

    const result = await response.json().catch(() => ({message: '登录失败'}));
    loginMessage.textContent = result.message || '登录失败';
});
