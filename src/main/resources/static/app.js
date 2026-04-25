const cardList = document.getElementById('cardList');
const configSearch = document.getElementById('configSearch');
const configSort = document.getElementById('configSort');
const configForm = document.getElementById('configForm');
const formMessage = document.getElementById('formMessage');
const refreshBtn = document.getElementById('refreshBtn');
const addConfigBtn = document.getElementById('addConfigBtn');
const logoutBtn = document.getElementById('logoutBtn');
const submitBtn = document.getElementById('submitBtn');
const addConfigModal = document.getElementById('addConfigModal');
const modalCloseBtn = document.getElementById('modalCloseBtn');
const modalTitle = document.getElementById('modalTitle');
const modalLead = document.querySelector('.modal-lead');
const difyInputMappingTable = document.getElementById('difyInputMappingTable');
const addDifyMappingBtn = document.getElementById('addDifyMappingBtn');
let editingConfigId = null;
let editingMaskedFields = {};
const cardMap = new Map();

async function ensureLogin() {
    const resp = await fetch('/api/auth/me');
    if (!resp.ok) {
        window.location.href = '/login.html';
    }
}

function getSearchQuery() {
    return configSearch ? String(configSearch.value).trim() : '';
}

function getSortParam() {
    if (!configSort) {
        return 'created';
    }
    const v = String(configSort.value).trim();
    return v === 'name' ? 'name' : 'created';
}

async function loadCards() {
    const q = getSearchQuery();
    const sort = getSortParam();
    const params = new URLSearchParams();
    if (q) {
        params.set('q', q);
    }
    if (sort !== 'created') {
        params.set('sort', sort);
    }
    const query = params.toString();
    const url = query ? `/api/bot-configs?${query}` : '/api/bot-configs';
    const response = await fetch(url);
    if (!response.ok) {
        cardList.innerHTML = '<p class="muted">加载失败，请重新登录。</p>';
        return;
    }
    const cards = await response.json();
    cardMap.clear();
    cards.forEach(card => cardMap.set(String(card.id), card));
    if (!cards.length) {
        if (q) {
            cardList.innerHTML = `
                <div class="panel empty-state">
                    <p class="muted">没有匹配「${escapeHtml(q)}」的配置，可换个关键词或清空搜索框。</p>
                </div>`;
        } else {
            cardList.innerHTML = `
                <div class="panel empty-state">
                    <p class="muted">还没有机器人配置。点击「添加配置」填写飞书与 Dify 参数即可创建。</p>
                </div>`;
        }
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
                <div><strong>工号鉴权</strong> ${card.employeeAuthEnabled ? '已启用' : '未启用'}</div>
                <div><strong>鉴权来源</strong> 飞书多维表格</div>
                <div><strong>接入方式</strong> 飞书 SDK 长连接</div>
                <div><strong>状态</strong> ${escapeHtml(card.lastStatusMessage || '-')}</div>
            </div>
            <div class="card-actions">
                <button type="button" data-action="toggle" data-id="${card.id}" data-enabled="${!card.longConnectionEnabled}">
                    ${card.longConnectionEnabled ? '关闭长连接' : '开启长连接'}
                </button>
                <button type="button" class="ghost-btn" data-action="edit" data-id="${card.id}" ${card.longConnectionEnabled ? 'disabled title="请先关闭长连接"' : ''}>修改配置</button>
                <button type="button" class="ghost-btn" data-action="export" data-id="${card.id}">导出记录</button>
                <button type="button" class="ghost-btn danger-btn" data-action="delete" data-id="${card.id}" data-robot-name="${encodeURIComponent(card.robotName)}">删除配置</button>
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

    document.querySelectorAll('[data-action="edit"]').forEach(button => {
        button.addEventListener('click', () => {
            const id = button.dataset.id;
            const card = cardMap.get(String(id));
            if (!card) {
                alert('未找到配置数据，请先刷新页面');
                return;
            }
            openEditModal(card);
        });
    });

    document.querySelectorAll('[data-action="delete"]').forEach(button => {
        button.addEventListener('click', async () => {
            const id = button.dataset.id;
            let robotLabel = '该配置';
            try {
                robotLabel = decodeURIComponent(button.dataset.robotName || '') || robotLabel;
            } catch {
                /* ignore malformed name */
            }
            if (!confirm(`确定删除「${robotLabel}」？将同时删除该配置下的对话导出数据，且不可恢复。`)) {
                return;
            }
            button.disabled = true;
            let response;
            try {
                response = await fetch(`/api/bot-configs/${id}`, {method: 'DELETE'});
            } catch {
                button.disabled = false;
                alert('网络异常，请稍后重试');
                return;
            }
            button.disabled = false;
            if (!response.ok) {
                let message = '删除失败';
                try {
                    const body = await response.json();
                    if (body && body.message) {
                        message = body.message;
                    }
                } catch {
                    /* ignore */
                }
                alert(message);
                return;
            }
            await loadCards();
        });
    });
}

function openAddModal() {
    configForm.reset();
    editingConfigId = null;
    editingMaskedFields = {};
    setSensitiveFieldRequired(true);
    modalTitle.textContent = '新增机器人配置';
    modalLead.textContent = '填写飞书与 Dify 参数后保存，即可在列表中看到新卡片。';
    submitBtn.textContent = '创建配置';
    setFormMessage('', 'info');
    resetDifyMappings();
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

function openEditModal(card) {
    editingConfigId = card.id;
    editingMaskedFields = {
        appSecret: card.appSecretMasked || '',
        difyApiKey: card.difyApiKeyMasked || '',
        verificationToken: card.verificationTokenMasked || '',
        encryptKey: card.encryptKeyMasked || ''
    };
    setSensitiveFieldRequired(false);
    modalTitle.textContent = '修改机器人配置';
    modalLead.textContent = '仅在长连接关闭状态下允许修改。已保存字段会回显，保持不改可直接提交。';
    submitBtn.textContent = '保存修改';
    configForm.reset();
    configForm.elements.robotName.value = card.robotName || '';
    configForm.elements.appId.value = card.appId || '';
    configForm.elements.appSecret.value = editingMaskedFields.appSecret;
    configForm.elements.verificationToken.value = editingMaskedFields.verificationToken;
    configForm.elements.encryptKey.value = editingMaskedFields.encryptKey;
    configForm.elements.difyUrl.value = card.difyUrl || '';
    configForm.elements.difyApiKey.value = editingMaskedFields.difyApiKey;
    resetDifyMappings(card.difyInputMappings || []);
    configForm.elements.employeeAuthEnabled.checked = !!card.employeeAuthEnabled;
    configForm.elements.employeeAuthDeniedReply.value = card.employeeAuthDeniedReply || '';
    configForm.elements.employeeAuthBitableAppToken.value = card.employeeAuthBitableAppToken || '';
    configForm.elements.employeeAuthBitableTableId.value = card.employeeAuthBitableTableId || '';
    configForm.elements.employeeAuthBitableViewId.value = card.employeeAuthBitableViewId || '';
    configForm.elements.employeeAuthBitableEmployeeField.value = card.employeeAuthBitableEmployeeField || '';
    setFormMessage('', 'info');
    resetSecretFieldVisibility();
    resetSubmitButton();
    addConfigModal.classList.add('is-open');
    addConfigModal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('modal-open');
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
    payload.difyInputMappings = collectDifyMappings();
    const creating = editingConfigId == null;
    if (!creating) {
        ['appSecret', 'difyApiKey', 'verificationToken', 'encryptKey'].forEach((field) => {
            if (editingMaskedFields[field] && payload[field] === editingMaskedFields[field]) {
                payload[field] = '';
            }
        });
    }
    payload.employeeAuthEnabled = formData.get('employeeAuthEnabled') === 'true';
    if (!payload.robotName || !payload.appId || !payload.difyUrl) {
        setFormMessage('请先补全机器人名称、App ID、Dify URL', 'error');
        resetSubmitButton();
        return;
    }
    if (creating && (!payload.appSecret || !payload.difyApiKey)) {
        setFormMessage('新建配置时，App Secret 和 Dify API Key 必填', 'error');
        resetSubmitButton();
        return;
    }
    if (payload.employeeAuthEnabled) {
        if (!payload.employeeAuthBitableAppToken || !payload.employeeAuthBitableTableId) {
            setFormMessage('启用多维表格鉴权后，App Token 和 Table/Sheet ID 不能为空', 'error');
            resetSubmitButton();
            return;
        }
    }

    payload.difyUrl = payload.difyUrl.replace(/\/+$/, '');

    let response;
    let result = {};
    const requestUrl = creating ? '/api/bot-configs' : `/api/bot-configs/${editingConfigId}`;
    const requestMethod = creating ? 'POST' : 'PUT';
    try {
        response = await fetch(requestUrl, {
            method: requestMethod,
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
    editingConfigId = null;
    editingMaskedFields = {};
    resetSecretFieldVisibility();
    setFormMessage(creating ? '创建成功' : '修改成功', 'success');
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

let searchDebounceTimer;
configSearch.addEventListener('input', () => {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(() => loadCards(), 280);
});

configSort.addEventListener('change', () => loadCards());

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
    submitBtn.textContent = editingConfigId == null ? '创建配置' : '保存修改';
}

function setSensitiveFieldRequired(required) {
    const appSecretInput = configForm.querySelector('input[name="appSecret"]');
    const difyApiKeyInput = configForm.querySelector('input[name="difyApiKey"]');
    if (appSecretInput) {
        appSecretInput.required = required;
    }
    if (difyApiKeyInput) {
        difyApiKeyInput.required = required;
    }
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

function collectDifyMappings() {
    const rows = Array.from(difyInputMappingTable.querySelectorAll('.dify-mapping-row'));
    const mappings = [];
    rows.forEach((row) => {
        const variableInput = row.querySelector('input[name="difyMappingVariable"]');
        const sourceSelect = row.querySelector('select[name="difyMappingSource"]');
        const variable = (variableInput?.value || '').trim();
        const source = (sourceSelect?.value || '').trim();
        if (!variable || !source) {
            return;
        }
        mappings.push({variable, source});
    });
    return mappings;
}

function addDifyMappingRow(data = {}) {
    const row = document.createElement('div');
    row.className = 'dify-mapping-row';
    row.innerHTML = `
        <input name="difyMappingVariable" placeholder="变量名，如 user_name" value="${escapeHtml(data.variable || '')}">
        <select name="difyMappingSource">
            <option value="display_name">展示名</option>
            <option value="employee_no">工号</option>
            <option value="full_name">姓名</option>
            <option value="email">邮箱</option>
            <option value="en_name">英文名</option>
            <option value="open_id">Open ID</option>
            <option value="union_id">Union ID</option>
        </select>
        <button type="button" class="ghost-btn dify-remove-row">删除</button>
    `;
    difyInputMappingTable.appendChild(row);
    const sourceSelect = row.querySelector('select[name="difyMappingSource"]');
    sourceSelect.value = data.source || 'display_name';
    row.querySelector('.dify-remove-row').addEventListener('click', () => {
        row.remove();
        ensureDifyMappingRows();
    });
}

function ensureDifyMappingRows() {
    if (difyInputMappingTable.querySelectorAll('.dify-mapping-row').length === 0) {
        resetDifyMappings();
    }
}

function resetDifyMappings(mappings = null) {
    difyInputMappingTable.innerHTML = '';
    const rows = Array.isArray(mappings) && mappings.length
        ? mappings
        : [
            {variable: 'feishu_sender_name', source: 'display_name'},
            {variable: 'feishu_employee_no', source: 'employee_no'}
        ];
    rows.forEach((item) => addDifyMappingRow(item));
}

addDifyMappingBtn.addEventListener('click', () => addDifyMappingRow());
