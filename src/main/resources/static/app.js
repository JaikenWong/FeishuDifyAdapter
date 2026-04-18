const cardList = document.getElementById('cardList');
const configForm = document.getElementById('configForm');
const formMessage = document.getElementById('formMessage');
const refreshBtn = document.getElementById('refreshBtn');
const addConfigBtn = document.getElementById('addConfigBtn');
const logoutBtn = document.getElementById('logoutBtn');
const submitBtn = document.getElementById('submitBtn');
const addConfigModal = document.getElementById('addConfigModal');
const modalCloseBtn = document.getElementById('modalCloseBtn');

async function ensureLogin() {
    const resp = await fetch('/api/auth/me');
    if (!resp.ok) {
        window.location.href = '/login.html';
    }
}

async function loadCards() {
    const response = await fetch('/api/bot-configs');
    if (!response.ok) {
        cardList.innerHTML = '<p class="muted">加载失败，请重新登录。</p>';
        return;
    }
    const cards = await response.json();
    if (!cards.length) {
        cardList.innerHTML = `
            <div class="panel empty-state">
                <p class="muted">还没有机器人配置。点击「添加配置」填写飞书与 Dify 参数即可创建。</p>
            </div>`;
        return;
    }
    cardList.innerHTML = cards.map(renderCard).join('');
    bindCardActions();
}

function renderCard(card) {
    const statusClass = card.longConnectionEnabled ? 'on' : 'off';
    const statusLabel = card.longConnectionEnabled ? '监听中' : '未开启';
    return `
        <article class="config-card">
            <div class="card-meta">
                <div>
                    <h2>${escapeHtml(card.robotName)}</h2>
                    <p class="muted">配置 ID: ${card.id}</p>
                </div>
                <span class="pill ${statusClass}">${statusLabel}</span>
            </div>
            <div class="meta-list">
                <div><strong>App ID</strong> ${escapeHtml(card.appId)}</div>
                <div><strong>App Secret</strong> ${escapeHtml(card.appSecretMasked)}</div>
                <div><strong>Dify URL</strong> ${escapeHtml(card.difyUrl)}</div>
                <div><strong>Dify Key</strong> ${escapeHtml(card.difyApiKeyMasked)}</div>
                <div><strong>接入方式</strong> 飞书 SDK 长连接</div>
                <div><strong>状态</strong> ${escapeHtml(card.lastStatusMessage || '-')}</div>
            </div>
            <div class="card-actions">
                <button type="button" data-action="toggle" data-id="${card.id}" data-enabled="${!card.longConnectionEnabled}">
                    ${card.longConnectionEnabled ? '关闭长连接' : '开启长连接'}
                </button>
                <button type="button" class="ghost-btn" data-action="export" data-id="${card.id}">导出记录</button>
            </div>
        </article>
    `;
}

function bindCardActions() {
    document.querySelectorAll('[data-action="toggle"]').forEach(button => {
        button.addEventListener('click', async () => {
            const id = button.dataset.id;
            const enabled = button.dataset.enabled;
            button.disabled = true;
            await fetch(`/api/bot-configs/${id}/long-connection?enabled=${enabled}`, {method: 'POST'});
            await loadCards();
        });
    });

    document.querySelectorAll('[data-action="export"]').forEach(button => {
        button.addEventListener('click', () => {
            window.location.href = `/api/bot-configs/${button.dataset.id}/export`;
        });
    });
}

function openAddModal() {
    configForm.reset();
    setFormMessage('', 'info');
    resetSecretFieldVisibility();
    resetSubmitButton();
    addConfigModal.classList.add('is-open');
    addConfigModal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('modal-open');
    const firstInput = configForm.querySelector('input[name="robotName"]');
    if (firstInput) {
        requestAnimationFrame(() => firstInput.focus());
    }
}

function closeAddModal() {
    addConfigModal.classList.remove('is-open');
    addConfigModal.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('modal-open');
    resetSubmitButton();
}

addConfigBtn.addEventListener('click', openAddModal);

modalCloseBtn.addEventListener('click', closeAddModal);

addConfigModal.querySelectorAll('[data-close-modal]').forEach(el => {
    el.addEventListener('click', closeAddModal);
});

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && addConfigModal.classList.contains('is-open')) {
        closeAddModal();
    }
});

configForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    setFormMessage('创建中...', 'info');
    submitBtn.disabled = true;
    submitBtn.textContent = '创建中...';

    const formData = new FormData(configForm);
    const payload = Object.fromEntries(
        Array.from(formData.entries()).map(([key, value]) => [key, String(value).trim()])
    );
    if (!payload.robotName || !payload.appId || !payload.appSecret || !payload.difyUrl || !payload.difyApiKey) {
        setFormMessage('请先补全所有必填项（带 * 的字段）', 'error');
        resetSubmitButton();
        return;
    }

    payload.difyUrl = payload.difyUrl.replace(/\/+$/, '');

    let response;
    let result = {};
    try {
        response = await fetch('/api/bot-configs', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        result = await response.json();
    } catch (error) {
        setFormMessage('网络异常，请稍后重试', 'error');
        resetSubmitButton();
        return;
    }

    if (!response.ok) {
        setFormMessage(result.message || '创建失败，请检查配置后重试', 'error');
        resetSubmitButton();
        return;
    }
    configForm.reset();
    resetSecretFieldVisibility();
    setFormMessage('创建成功', 'success');
    resetSubmitButton();
    closeAddModal();
    await loadCards();
});

configForm.addEventListener('reset', () => {
    setFormMessage('', 'info');
    resetSecretFieldVisibility();
});

document.querySelectorAll('[data-toggle-target]').forEach(button => {
    button.addEventListener('click', () => {
        const targetName = button.dataset.toggleTarget;
        const input = configForm.querySelector(`input[name="${targetName}"]`);
        if (!input) {
            return;
        }
        const isPassword = input.type === 'password';
        input.type = isPassword ? 'text' : 'password';
        button.textContent = isPassword ? '隐藏' : '显示';
    });
});

refreshBtn.addEventListener('click', loadCards);

logoutBtn.addEventListener('click', async () => {
    await fetch('/api/auth/logout', {method: 'POST'});
    window.location.href = '/login.html';
});

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
}

function setFormMessage(message, type) {
    formMessage.textContent = message;
    formMessage.classList.remove('success', 'error', 'info');
    formMessage.classList.add(type);
}

function resetSubmitButton() {
    submitBtn.disabled = false;
    submitBtn.textContent = '创建配置';
}

function resetSecretFieldVisibility() {
    const secretNames = ['appSecret', 'encryptKey', 'difyApiKey'];
    secretNames.forEach((name) => {
        const input = configForm.querySelector(`input[name="${name}"]`);
        const btn = configForm.querySelector(`[data-toggle-target="${name}"]`);
        if (input) {
            input.type = 'password';
        }
        if (btn) {
            btn.textContent = '显示';
        }
    });
}

ensureLogin().then(loadCards);
