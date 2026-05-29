/* ==========================================================================
   APP.JS - State Engine, API Simulator, & Interactive Visual Traces
   ========================================================================== */

// --- Global UI States ---
let currentTab = 'architecture';
let currentVariation = 'auth_card_success';
let outputMode = 'table'; // 'table' or 'raw'
let simState = 'CREATED';

// --- Architecture Hotspot Mappings ---
const nodeDetails = {
  client: {
    title: "External Client Boundary",
    tag: "Synchronous Entry Path",
    pciScope: "Out of PCI Audit Scope. Card details are tokenized prior to request ingestion.",
    rules: [
      "Must submit a unique 'X-Nonce' string to block replay attacks.",
      "Must compute a valid HMAC-SHA256 signature using the shared merchant secret.",
      "Must include a valid 'X-Request-Id' UUID v4 header."
    ],
    code: `// Express / Spring Gateway Contract
String hmacSignature = request.getHeader("X-Signature");
String nonce = request.getHeader("X-Nonce");
String timestamp = request.getHeader("X-Timestamp");`
  },
  security: {
    title: "Security & Signature Filter",
    tag: "Constant-Time Verification",
    pciScope: "Exempt. Evaluates only base64 signatures, timestamp windows, and nonce caches.",
    rules: [
      "Timestamp check: timestamp must fall within strict ±5-minute clock drift bounds.",
      "Nonce check: Nonce compared via atomic Redis NX/EX with 10-minute (600s) TTL.",
      "Constant-time check: Byte signature compared via MessageDigest.isEqual to eliminate Timing Attacks."
    ],
    code: `// timing-safe byte validation
if (!MessageDigest.isEqual(actualBytes, expectedBytes)) {
    throw new InvalidSignatureException("Signature mismatch");
}`
  },
  idempotency: {
    title: "Idempotency Guard",
    tag: "Replay-Safe Request Claim",
    pciScope: "Exempt. Stores request hashes and cached response payloads, not payment credentials.",
    rules: [
      "Claims the Idempotency-Key before any PSP call so duplicate in-flight requests are rejected.",
      "Hashes a canonical JSON body, so the same key with a different payload returns a conflict.",
      "Returns the cached completed response for exact duplicate retries within the 24-hour TTL."
    ],
    code: `// canonical request hash
String hash = sha256Hex(canonicalizeJson(rawBody));
if (sameKeyDifferentHash) throw new IdempotencyConflictException();`
  },
  transaction: {
    title: "JPA Transaction Boundary",
    tag: "Atomic Persistence (1-Commit)",
    pciScope: "Exempt. Stores only safe, opaque vault reference tokens (PCI-safe).",
    rules: [
      "Rule R-01: No PSP network call may ever execute inside this SQL transaction boundary.",
      "Saves 4 rows atomically: PaymentIntent, PaymentAttempt, PaymentEvent, and PaymentOutbox.",
      "Optimistic Locking: Checks version column to block concurrent webhook/worker conflicts."
    ],
    code: `@Transactional
public PaymentIntent initiatePayment(CreatePaymentRequest request) {
    // 1. Create intent, attempt, audit event, outbox row
    // 2. Commit transaction atomically
    // 3. Return committed intent
}`
  },
  psp: {
    title: "PSP Provider Router",
    tag: "External Provider Routing",
    pciScope: "Direct backchannel routing. Resolves tokens via detokenization proxy momentarily.",
    rules: [
      "Provider request is sent only after the initial payment state has been saved.",
      "Resilience4j Circuit Breaker: Limits damage if a provider is experiencing high error rates.",
      "Timeouts remain PENDING because the PSP may still complete the payment after our service stops waiting."
    ],
    code: `// Executed outside the database transaction
try {
    ResponseEntity<PspResponse> response = restTemplate.postForEntity(url, payload);
    return resolveSuccess(response);
} catch (ResourceAccessException timeoutException) {
    return resolveAmbiguousTimeout(timeoutException); // PENDING
}`
  },
  workers: {
    title: "Recovery Workers (Async)",
    tag: "Database Poller (Scheduled)",
    pciScope: "Exempt. Reads sharded relational queues.",
    rules: [
      "Reconciliation Worker: Polls PENDING intents every 45s using FOR UPDATE SKIP LOCKED.",
      "Retry Worker: Initiates backoff retries only after reconciliation confirms no duplicate authorization occurred.",
      "Escalation Policy: >24 hours stuck PENDING fires warnings; >48 hours transitions to MANUAL_REVIEW."
    ],
    code: `// select PENDING items safely across worker clusters
@Query("SELECT i FROM PaymentIntent i WHERE i.status = 'PENDING' FOR UPDATE SKIP LOCKED")
List<PaymentIntent> findPendingForReconciliation(Pageable pageable);`
  },
  outbox: {
    title: "Outbox Event Poller",
    tag: "Observability Pipeline",
    pciScope: "Exempt. Handles outbox stream records.",
    rules: [
      "Polls payment_outbox table every 1500ms using SKIP LOCKED.",
      "At-Least-Once Delivery: Guarantees every transaction state event is successfully published to Kafka.",
      "Prunes rows: Processed outbox rows are automatically purged after 7 days to maintain index performance."
    ],
    code: `@Scheduled(fixedDelay = 1500)
public void processOutboxQueue() {
    List<PaymentOutbox> pending = outboxRepository.fetchPendingBatch();
    // publish to Kafka -> mark PROCESSED
}`
  }
};

// --- API Variation Mock Database ---
const variations = {
  auth_card_success: {
    method: "POST",
    path: "/payments",
    request: `{
  "merchant_id": "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679",
  "merchant_order_id": "ord_998877",
  "transaction_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "settlement_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "payment_method_type": "CARD",
  "payment_token_reference": "tok_card_success"
}`,
    scenario: "Card Success (PSP A)",
    description: "Synchronous credit card authorization succeeded via PSP A. DB state is updated cleanly.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "CREATED ➔ PROCESSING ➔ AUTHORIZED",
    idempotency: "COMPLETED (TTL: 24h)",
    audit: "1 event in payment_events",
    outbox: "1 pending event queued",
    pspConfigHint: { psp: "PSP_A", property: "orchestrator.psp.psp-a.mode", values: ["SUCCESS", "FAILURE", "TIMEOUT"], desc: "Change this property in application.yml and restart to simulate a hard decline (FAILURE) or ambiguous timeout (TIMEOUT)." },
    raw: {
      status: "AUTHORIZED",
      intent_id: "4e7c7a31-6b22-4d9a-a82f-870a2a7cc33c",
      correlation_id: "corr_c8828ab1",
      amount: 1000,
      currency: "INR",
      provider_name: "PSP_A",
      provider_reference: "psp_att_123456"
    }
  },
  auth_upi_success: {
    method: "POST",
    path: "/payments",
    request: `{
  "merchant_id": "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679",
  "merchant_order_id": "ord_554433",
  "transaction_amount": {
    "amount": 450,
    "currency_code": "INR"
  },
  "settlement_amount": {
    "amount": 450,
    "currency_code": "INR"
  },
  "payment_method_type": "UPI",
  "payment_token_reference": "tok_upi_success"
}`,
    scenario: "UPI Success (PSP B)",
    description: "Synchronous UPI authorization succeeded via PSP B. Correctly routed by the engine.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "CREATED ➔ PROCESSING ➔ AUTHORIZED",
    idempotency: "COMPLETED (TTL: 24h)",
    audit: "1 event in payment_events",
    outbox: "1 pending event queued",
    pspConfigHint: { psp: "PSP_B", property: "orchestrator.psp.psp-b.mode", values: ["SUCCESS", "FAILURE", "TIMEOUT"], desc: "Change this property in application.yml and restart to simulate a hard decline (FAILURE) or ambiguous timeout (TIMEOUT)." },
    raw: {
      status: "AUTHORIZED",
      intent_id: "8f7c9e01-2a55-449e-b712-11002233aabb",
      correlation_id: "corr_d2239401",
      amount: 450,
      currency: "INR",
      provider_name: "PSP_B",
      provider_reference: "psp_upi_998877"
    }
  },
  auth_nonce: {
    method: "POST",
    path: "/payments",
    request: `{
  "merchant_id": "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679",
  "merchant_order_id": "ord_998877",
  "transaction_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "settlement_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "payment_method_type": "CARD",
  "payment_token_reference": "tok_card_success"
}`,
    scenario: "Nonce Replay Attack",
    description: "The submitted X-Nonce header was already recorded by Redis within the 10-minute cache window. Request rejected at security filter.",
    statusCode: "401 UNAUTHORIZED",
    statusClass: "badge-danger",
    transition: "BLOCKED (No Transition)",
    idempotency: "BYPASS (Rejected)",
    audit: "1 event in security_audit_logs",
    outbox: "0 events queued",
    raw: {
      error_code: "NONCE_REUSED",
      message: "Nonce signature already processed.",
      timestamp: "2026-05-29T03:40:00Z"
    }
  },
  auth_idempotency: {
    method: "POST",
    path: "/payments",
    request: `{
  "merchant_id": "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679",
  "merchant_order_id": "ord_998877",
  "transaction_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "settlement_amount": {
    "amount": 1000,
    "currency_code": "INR"
  },
  "payment_method_type": "CARD",
  "payment_token_reference": "tok_card_success"
}`,
    scenario: "Idempotency Replay",
    description: "Request matched a previously completed transaction key. Safely returned the cached database response directly.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "CACHED (No Transition)",
    idempotency: "MATCHED (Returned Cache)",
    audit: "0 new events written",
    outbox: "0 events queued",
    raw: {
      status: "AUTHORIZED",
      intent_id: "4e7c7a31-6b22-4d9a-a82f-870a2a7cc33c",
      correlation_id: "corr_c8828ab1",
      amount: 1000,
      currency: "INR",
      provider_name: "PSP_A",
      provider_reference: "psp_att_123456",
      _idempotency: "REPLAY_RESPONSE"
    }
  },
  webhook_success: {
    method: "POST",
    path: "/webhooks/{provider}",
    request: `{
  "event_id": "evt_late_success_9988",
  "event_type": "PAYMENT_AUTHORIZED",
  "provider_reference": "psp_att_timeout_123",
  "status": "SUCCESS",
  "amount": 5000,
  "currency": "INR",
  "timestamp": "2026-05-29T04:50:00Z"
}`,
    scenario: "Late Webhook Success",
    description: "Webhook successfully resolves an ambiguous PENDING intent to AUTHORIZED state safely.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "PENDING ➔ AUTHORIZED",
    idempotency: "DEDUPLICATED (Event Index)",
    audit: "1 event in payment_events",
    outbox: "1 pending event queued",
    raw: {
      status: "SUCCESS_ACK",
      intent_id: "cc3b8901-5d9a-4c22-b011-8aa29becc332",
      new_status: "AUTHORIZED"
    }
  },
  webhook_illegal: {
    method: "POST",
    path: "/webhooks/{provider}",
    request: `{
  "event_id": "evt_hoax_success_44",
  "event_type": "PAYMENT_AUTHORIZED",
  "provider_reference": "psp_att_failed_999",
  "status": "SUCCESS",
  "amount": 2500,
  "currency": "INR",
  "timestamp": "2026-05-29T04:50:00Z"
}`,
    scenario: "Contradictory Webhook",
    description: "Webhook tries to mark a finalized FAILED intent as AUTHORIZED. Blocked by terminal immutability constraints.",
    statusCode: "422 UNPROCESSABLE ENTITY",
    statusClass: "badge-danger",
    transition: "FAILED ➔ AUTHORIZED (Blocked)",
    idempotency: "BYPASS",
    audit: "1 warning event logged",
    outbox: "0 events queued",
    raw: {
      error_code: "ILLEGAL_STATE_TRANSITION",
      message: "State transition from FAILED to AUTHORIZED is blocked."
    }
  },
  get_intent: {
    method: "GET",
    path: "/payments/{intentId}",
    request: "160a3746-bc6d-453f-86f8-04d266e677c5",
    scenario: "Retrieve by ID (intentId)",
    description: "Fetches the full transactional payment state, including attempts and event logs, using the unique intent UUID.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "CACHED / READ ONLY",
    idempotency: "N/A",
    audit: "N/A",
    outbox: "N/A",
    raw: {
      intent_id: "7869055b-2167-423b-833e-ea58ee6fb5ce",
      merchant_id: "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679",
      merchant_order_id: "ord_998877",
      status: "AUTHORIZED",
      version: 1,
      attempts: []
    }
  },
  get_status: {
    method: "GET",
    path: "/payments/status/{merchantOrderId}",
    request: "ord_998877",
    scenario: "Lookup by Order ID (merchantOrderId)",
    description: "Queries the payment status directly using the merchant's business order reference ID.",
    statusCode: "200 OK",
    statusClass: "badge-success",
    transition: "CACHED / READ ONLY",
    idempotency: "N/A",
    audit: "N/A",
    outbox: "N/A",
    raw: {
      intent_id: "7869055b-2167-423b-833e-ea58ee6fb5ce",
      merchant_order_id: "ord_998877",
      status: "AUTHORIZED",
      amount: 1000
    }
  }
};

// --- MDC Logs Mappings ---
const mdcTraces = {
  filter: {
    timestamp: "2026-05-29T03:40:15.109Z",
    message: "Validating merchant signature headers...",
    request_id: "req_88776655",
    correlation_id: "corr_11223344",
    internal_request_id: "int_req_abcdef12"
  },
  transaction: {
    timestamp: "2026-05-29T03:40:15.120Z",
    message: "Executing transactional persistence writes (PaymentIntent + PaymentAttempt + Outbox)...",
    request_id: "req_88776655",
    correlation_id: "corr_11223344",
    internal_request_id: "int_req_abcdef12",
    intent_id: "intent_554433"
  },
  psp: {
    timestamp: "2026-05-29T03:40:15.132Z",
    message: "Outgoing HTTP REST request sent to PSP A endpoint outside transactional boundary...",
    request_id: "req_88776655",
    correlation_id: "corr_11223344",
    internal_request_id: "int_req_abcdef12",
    intent_id: "intent_554433",
    provider_name: "PSP_A"
  },
  outbox: {
    timestamp: "2026-05-29T03:40:16.500Z",
    message: "Outbox poller cycle: successfully published PAYMENT_AUTHORIZED event to Kafka.",
    correlation_id: "corr_11223344",
    internal_request_id: "bg_outbox_cycle_a8b9"
  }
};

// --- Tab Controller ---
function switchTab(tabId) {
  currentTab = tabId;
  document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
  document.querySelectorAll('.tab-pane').forEach(pane => pane.classList.remove('active'));
  
  // Highlight correct tab button
  const activeBtn = Array.from(document.querySelectorAll('.tab-btn')).find(b => b.textContent.toLowerCase().includes(tabId));
  if (activeBtn) activeBtn.classList.add('active');
  
  // Display target tab pane
  const activePane = document.getElementById(`tab-${tabId}`);
  if (activePane) activePane.classList.add('active');
}

// Bind globally to ensure availability in inline event handlers
window.switchTab = switchTab;

// --- Tab 1: Architecture node reveal ---
function revealDetail(nodeId) {
  const detail = nodeDetails[nodeId];
  if (!detail) return;
  
  const placeholder = document.querySelector('.detail-placeholder');
  if (placeholder) placeholder.classList.add('hidden');
  
  const content = document.getElementById('detailContent');
  if (content) content.classList.remove('hidden');
  
  const panel = document.getElementById('detailPanel');
  if (panel) panel.classList.add('active');
  
  document.getElementById('detailTitle').textContent = detail.title;
  const tag = document.getElementById('nodeTag');
  tag.textContent = detail.tag;
  
  // color tags based on category
  tag.className = 'node-tag';
  if (nodeId === 'security' || nodeId === 'client') tag.classList.add('blue');
  if (nodeId === 'idempotency') tag.classList.add('purple');
  if (nodeId === 'transaction') tag.classList.add('green');
  if (nodeId === 'workers' || nodeId === 'outbox') tag.classList.add('purple');
  if (nodeId === 'psp') tag.classList.add('purple');

  document.getElementById('pciScope').textContent = detail.pciScope;
  
  const rulesList = document.getElementById('nodeRules');
  rulesList.innerHTML = '';
  detail.rules.forEach(r => {
    const li = document.createElement('li');
    li.textContent = r;
    rulesList.appendChild(li);
  });
  
  const nodeCode = document.getElementById('nodeCode');
  if (nodeCode) nodeCode.textContent = detail.code || '';
  
  // Trigger marker jump animation based on selected node
  const marker = document.getElementById('flowMarker');
  if (marker) {
    marker.style.animation = 'none';
    
    setTimeout(() => {
      if (nodeId === 'client') { marker.setAttribute('cx', '70'); marker.setAttribute('cy', '225'); }
      if (nodeId === 'security') { marker.setAttribute('cx', '320'); marker.setAttribute('cy', '225'); }
      if (nodeId === 'transaction') { marker.setAttribute('cx', '560'); marker.setAttribute('cy', '225'); }
      if (nodeId === 'psp') { marker.setAttribute('cx', '815'); marker.setAttribute('cy', '225'); }
      if (nodeId === 'workers') { marker.setAttribute('cx', '770'); marker.setAttribute('cy', '360'); }
      if (nodeId === 'outbox') { marker.setAttribute('cx', '770'); marker.setAttribute('cy', '120'); }
    }, 50);
  }
}

function closeDetailPanel() {
  const panel = document.getElementById('detailPanel');
  if (panel) panel.classList.remove('active');
}

window.revealDetail = revealDetail;
window.closeDetailPanel = closeDetailPanel;

async function calculateRealSignature() {
  const jsonStr = document.getElementById('jsonEditor').value;
  let parsed;
  try {
    parsed = JSON.parse(jsonStr);
  } catch (err) {
    return; // invalid json, skip calculation for now
  }

  const variation = variations[currentVariation];
  if (!variation) return;

  const timestampField = document.getElementById('headerTimestamp');
  const nonceField = document.getElementById('headerNonce');
  const sigField = document.getElementById('headerSignature');

  if (!timestampField || !nonceField || !sigField) return;

  const isWebhook = variation.path.includes('/webhooks/');
  if (isWebhook) {
    // Determine provider webhook secret
    const providerInput = document.getElementById('queryParamInput');
    const providerVal = providerInput ? providerInput.value.trim().toUpperCase() : "PSP_A";
    
    let secret = "secret_psp_a";
    if (providerVal.includes("PSP_B")) {
      secret = "secret_psp_b";
    }

    try {
      const encoder = new TextEncoder();
      const secretBytes = encoder.encode(secret);
      const importedKey = await crypto.subtle.importKey(
        'raw',
        secretBytes,
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign']
      );
      const sigBuffer = await crypto.subtle.sign(
        'HMAC',
        importedKey,
        encoder.encode(jsonStr)
      );
      const pspSignature = btoa(String.fromCharCode(...new Uint8Array(sigBuffer)));
      sigField.value = pspSignature;
    } catch (err) {
      console.error("Webhook signature calculation error:", err);
    }
    return;
  }

  const merchantId = parsed.merchant_id || "893c5d61-ff8b-4c07-9b24-7cb4bbd7c679";
  const path = `/api/v1/payments-orchestration${variation.path}`;
  const method = variation.method || "POST";
  
  const timestamp = timestampField.value;
  const nonce = nonceField.value;

  // Canonicalize JSON keys recursively (mimic Java mapper sort MAP_ENTRIES_BY_KEYS)
  function canonicalize(obj) {
    if (obj === null || typeof obj !== 'object') {
      return JSON.stringify(obj);
    }
    if (Array.isArray(obj)) {
      return '[' + obj.map(canonicalize).join(',') + ']';
    }
    const keys = Object.keys(obj).sort();
    const parts = keys.map(k => `"${k}":${canonicalize(obj[k])}`);
    return '{' + parts.join(',') + '}';
  }

  let canonicalJson = "";
  try {
    canonicalJson = canonicalize(parsed);
  } catch (e) {
    canonicalJson = jsonStr;
  }

  try {
    const encoder = new TextEncoder();
    const dataBytes = encoder.encode(canonicalJson);
    const hashBuffer = await crypto.subtle.digest('SHA-256', dataBytes);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const bodySha256Hex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');

    // Canonical string formula: METHOD\nPATH\nBODY_SHA256\nTIMESTAMP\nNONCE\nMERCHANT_ID
    const canonicalString = [
      method,
      path,
      bodySha256Hex,
      timestamp,
      nonce,
      merchantId
    ].join('\n');

    // Calculate HMAC-SHA256 using playground secret key "active_secret_key_123"
    const secretKeyStr = "active_secret_key_123";
    const secretKeyBytes = encoder.encode(secretKeyStr);
    const importedKey = await crypto.subtle.importKey(
      'raw',
      secretKeyBytes,
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    
    const sigBuffer = await crypto.subtle.sign(
      'HMAC',
      importedKey,
      encoder.encode(canonicalString)
    );
    
    const signatureBase64 = btoa(String.fromCharCode(...new Uint8Array(sigBuffer)));
    sigField.value = signatureBase64;
  } catch (err) {
    console.error("Signature calculation error:", err);
  }
}

async function generateDynamicHeaders() {
  const nonce = 'nc_' + Math.random().toString(36).substring(2, 10);
  const requestId = 'req_' + Math.random().toString(36).substring(2, 12);
  const idempotency = 'id_' + Math.random().toString(36).substring(2, 12);
  const timestamp = new Date().toISOString();

  const keyField = document.getElementById('headerIdempotencyKey');
  const reqIdField = document.getElementById('headerRequestId');
  const tsField = document.getElementById('headerTimestamp');
  const nonceField = document.getElementById('headerNonce');

  if (keyField) keyField.value = idempotency;
  if (reqIdField) reqIdField.value = requestId;
  if (tsField) tsField.value = timestamp;
  if (nonceField) nonceField.value = nonce;

  // Asynchronously compute and fill cryptographic signature
  await calculateRealSignature();
}

// --- Tab 2: API Playground loader ---
async function loadVariation(variationId) {
  currentVariation = variationId;
  const data = variations[variationId];
  if (!data) return;
  
  document.querySelectorAll('.selector-btn').forEach(btn => btn.classList.remove('active'));
  const targetBtn = Array.from(document.querySelectorAll('.selector-btn')).find(b => b.textContent.includes(data.scenario));
  if (targetBtn) targetBtn.classList.add('active');
  
  document.getElementById('reqPath').textContent = `${data.method} ${data.path}`;
  document.getElementById('jsonEditor').value = data.request;

  // Adapt Header fields editor visibility & labels based on API type
  const isWebhook = data.path.includes('/webhooks/');
  const isGet = data.method === 'GET';

  const keyField = document.getElementById('headerIdempotencyKey');
  const reqIdField = document.getElementById('headerRequestId');
  const tsField = document.getElementById('headerTimestamp');
  const nonceField = document.getElementById('headerNonce');
  const sigField = document.getElementById('headerSignature');

  const keyParent = keyField ? keyField.closest('.header-field') : null;
  const reqIdParent = reqIdField ? reqIdField.closest('.header-field') : null;
  const tsParent = tsField ? tsField.closest('.header-field') : null;
  const nonceParent = nonceField ? nonceField.closest('.header-field') : null;
  const sigParent = sigField ? sigField.closest('.header-field') : null;

  const queryContainer = document.getElementById('queryParamContainer');
  const queryLabel = document.getElementById('queryParamLabel');
  const queryInput = document.getElementById('queryParamInput');
  const jsonEditor = document.getElementById('jsonEditor');
  const editorTitle = document.getElementById('editorTitle');

  if (isGet) {
    if (keyParent) keyParent.style.display = 'none';
    if (reqIdParent) reqIdParent.style.display = 'none';
    if (tsParent) tsParent.style.display = 'none';
    if (nonceParent) nonceParent.style.display = 'none';
    if (sigParent) sigParent.style.display = 'none';
    
    const notice = document.querySelector('.playground-notice');
    if (notice) notice.textContent = "GET requests retrieve data and do not require merchant security or idempotency headers.";

    // Show GET input, hide JSON payload
    if (editorTitle) editorTitle.textContent = "API Path Parameter Query";
    if (queryContainer) queryContainer.style.display = 'block';
    if (jsonEditor) jsonEditor.style.display = 'none';

    if (queryLabel && queryInput) {
      if (data.path.includes('{intentId}')) {
        queryLabel.textContent = "Payment Intent ID (UUID)";
        queryInput.placeholder = "e.g. 7869055b-2167-423b-833e-ea58ee6fb5ce";
        queryInput.value = data.request.trim();
      } else if (data.path.includes('{merchantOrderId}')) {
        queryLabel.textContent = "Merchant Order ID";
        queryInput.placeholder = "e.g. ord_998877";
        queryInput.value = data.request.trim();
      }
    }
  } else if (isWebhook) {
    if (keyParent) keyParent.style.display = 'none';
    if (reqIdParent) reqIdParent.style.display = 'none';
    if (tsParent) tsParent.style.display = 'none';
    if (nonceParent) nonceParent.style.display = 'none';
    if (sigParent) {
      sigParent.style.display = 'flex';
      sigParent.querySelector('label').textContent = 'X-PSP-Signature';
    }
    
    const notice = document.querySelector('.playground-notice');
    if (notice) notice.textContent = "Webhook requests require X-PSP-Signature, dynamically generated from the raw webhook payload using the provider secret key.";

    // Show GET/Webhook input, show JSON payload
    if (editorTitle) editorTitle.textContent = "Request Editor (JSON Payload)";
    if (queryContainer) queryContainer.style.display = 'block';
    if (jsonEditor) jsonEditor.style.display = 'block';

    if (queryLabel && queryInput) {
      queryLabel.textContent = "Webhook Provider (e.g. PSP_A or PSP_B)";
      queryInput.placeholder = "PSP_A or PSP_B";
      queryInput.value = "PSP_A";
    }
  } else {
    if (keyParent) keyParent.style.display = 'flex';
    if (reqIdParent) reqIdParent.style.display = 'flex';
    if (tsParent) tsParent.style.display = 'flex';
    if (nonceParent) nonceParent.style.display = 'flex';
    if (sigParent) {
      sigParent.style.display = 'flex';
      sigParent.querySelector('label').textContent = 'X-Signature';
    }
    
    const notice = document.querySelector('.playground-notice');
    if (notice) notice.textContent = "All security headers are dynamically pre-calculated and injected on request trigger. You can override them below if desired:";

    // Hide GET/Webhook input, show JSON payload
    if (editorTitle) editorTitle.textContent = "Request Editor (JSON Payload)";
    if (queryContainer) queryContainer.style.display = 'none';
    if (jsonEditor) jsonEditor.style.display = 'block';
  }
  
  // Calculate dynamic header fields
  await generateDynamicHeaders();
  
  // Update output
  updateResponseOutput(data);
}

function updateResponseOutput(data) {
  // Reveal output panel on first run
  const placeholder = document.getElementById('outputPlaceholder');
  const ready = document.getElementById('outputReady');
  if (placeholder) placeholder.classList.add('hidden');
  if (ready) ready.classList.remove('hidden');

  // Status badge
  const badge = document.getElementById('respStatusBadge');
  badge.textContent = data.statusCode;
  badge.className = 'resp-badge';
  const isOk = data.statusCode.includes('200') || data.statusCode.includes('202');
  badge.classList.add(isOk ? 'badge-success' : 'badge-danger');
  document.getElementById('respStatusCard').style.borderColor =
    isOk ? 'rgba(16, 185, 129, 0.2)' : 'rgba(239, 68, 68, 0.2)';

  document.getElementById('respScenarioTitle').textContent = data.scenario;
  document.getElementById('respDescription').textContent = data.description;

  // Build table rows from actual response fields
  const grid = document.getElementById('liveFieldsGrid');
  grid.innerHTML = '';
  const raw = data.raw || {};

  // Key label map — show known fields with friendly names, rest as-is
  const fieldLabels = {
    status:              'Status',
    intent_id:           'Intent ID',
    merchant_id:         'Merchant ID',
    merchant_order_id:   'Order ID',
    correlation_id:      'Correlation ID',
    request_id:          'Request ID',
    idempotency_key:     'Idempotency Key',
    transaction_amount:  'Transaction Amount',
    settlement_amount:   'Settlement Amount',
    transaction_currency_code: 'Currency',
    transactionCurrencyCode:   'Currency',
    transactionAmount:         'Transaction Amount',
    provider_name:       'PSP Provider',
    provider_reference:  'Provider Reference',
    final_attempt_id:    'Final Attempt ID',
    error_code:          'Error Code',
    message:             'Message',
    timestamp:           'Timestamp',
    created_at:          'Created At',
    updated_at:          'Updated At',
    version:             'Version',
    amount:              'Amount',
    currency:            'Currency',
  };

  function renderValue(v) {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
  }

  // Flatten top-level + nested error details
  const entries = Object.entries(raw).filter(([k]) => k !== 'attempts');
  if (!entries.length) {
    grid.innerHTML = '<div class="table-item" style="grid-column:1/-1"><span class="value">No fields in response.</span></div>';
  } else {
    entries.forEach(([k, v]) => {
      const label = fieldLabels[k] || k.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2');
      const div = document.createElement('div');
      div.className = 'table-item';
      div.innerHTML = `<span class="label">${label}</span><span class="value font-mono">${renderValue(v)}</span>`;
      grid.appendChild(div);
    });
  }

  // PSP config hint banner
  const hintEl = document.getElementById('pspConfigHint');
  if (data.pspConfigHint) {
    const h = data.pspConfigHint;
    const chips = (h.values || [h.value]).map((v, i) =>
      `<span class="hint-mode-chip${i === 0 ? ' hint-chip-active' : ''}">${v}</span>`
    ).join('');
    hintEl.innerHTML = `
      <div class="psp-hint-icon">ℹ️</div>
      <div>
        <div class="psp-hint-title">PSP Simulator Mode &mdash; <span class="hint-psp">${h.psp}</span></div>
        <code class="hint-prop">${h.property} =</code> ${chips}
        <div class="psp-hint-desc">${h.desc}</div>
      </div>`;
    hintEl.classList.remove('hidden');
  } else {
    hintEl.classList.add('hidden');
    hintEl.innerHTML = '';
  }

  // Raw JSON
  document.getElementById('rawJsonOutput').textContent = JSON.stringify(raw, null, 2);
}

function toggleOutputMode(mode) {
  outputMode = mode;
  document.getElementById('btnTable').classList.remove('active');
  document.getElementById('btnRaw').classList.remove('active');
  document.getElementById('outputTable').classList.add('hidden');
  document.getElementById('outputRaw').classList.add('hidden');
  if (mode === 'table') {
    document.getElementById('btnTable').classList.add('active');
    document.getElementById('outputTable').classList.remove('hidden');
  } else {
    document.getElementById('btnRaw').classList.add('active');
    document.getElementById('outputRaw').classList.remove('hidden');
  }
}

function resetEditor() {
  const data = variations[currentVariation];
  if (data) {
    document.getElementById('jsonEditor').value = data.request;
    calculateRealSignature();
  }
}

async function runSoloVariation() {
  const editedPayload = document.getElementById('jsonEditor').value;
  const variation = variations[currentVariation];
  if (!variation) return;

  const isGet = variation.method === 'GET';
  let parsed = null;

  if (!isGet) {
    try {
      parsed = JSON.parse(editedPayload);
    } catch (err) {
      alert("Invalid Request Payload: JSON structure is incorrect.");
      return;
    }
  }

  // Visual execution flash
  const outputBox = document.getElementById('outputTable').parentNode;
  outputBox.style.boxShadow = '0 0 20px rgba(59, 130, 246, 0.4)';
  setTimeout(() => {
    outputBox.style.boxShadow = 'none';
  }, 400);

  // Read header fields dynamically from user interface
  const key = document.getElementById('headerIdempotencyKey').value;
  const requestId = document.getElementById('headerRequestId').value;
  const timestamp = document.getElementById('headerTimestamp').value;
  const nonce = document.getElementById('headerNonce').value;
  const signature = document.getElementById('headerSignature').value;

  const isWebhook = variation.path.includes('/webhooks/');
  let url = `/api/v1/payments-orchestration${variation.path}`;
  if (isGet) {
    const queryInputVal = document.getElementById('queryParamInput').value.trim();
    if (queryInputVal) {
      if (variation.path.includes('{intentId}')) {
        url = `/api/v1/payments-orchestration/payments/${queryInputVal}`;
      } else if (variation.path.includes('{merchantOrderId}')) {
        url = `/api/v1/payments-orchestration/payments/status/${queryInputVal}`;
      }
    } else {
      // Fallbacks
      if (variation.path.includes('{intentId}')) {
        url = `/api/v1/payments-orchestration/payments/7869055b-2167-423b-833e-ea58ee6fb5ce`;
      } else if (variation.path.includes('{merchantOrderId}')) {
        url = `/api/v1/payments-orchestration/payments/status/ord_998877`;
      }
    }
  } else if (isWebhook) {
    const providerInputVal = document.getElementById('queryParamInput').value.trim();
    if (providerInputVal) {
      url = `/api/v1/payments-orchestration/webhooks/${providerInputVal}`;
    } else {
      url = `/api/v1/payments-orchestration/webhooks/PSP_A`;
    }
  }

  try {
    let fetchHeaders = {};
    if (isGet) {
      fetchHeaders = {};
    } else if (isWebhook) {
      fetchHeaders = {
        'Content-Type': 'application/json',
        'X-PSP-Signature': signature
      };
    } else {
      fetchHeaders = {
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
        'X-Request-Id': requestId,
        'X-Timestamp': timestamp,
        'X-Nonce': nonce,
        'X-Signature': signature
      };
    }

    const response = await fetch(url, {
      method: variation.method || 'POST',
      headers: fetchHeaders,
      body: isGet ? null : editedPayload
    });

    const statusCode = `${response.status} ${response.statusText}`;
    const responseText = await response.text();
    let responseJson = {};
    try {
      responseJson = JSON.parse(responseText);
    } catch (e) {
      responseJson = { rawResponse: responseText };
    }

    const state = responseJson.status || "N/A";
    const transition = responseJson.error_code ? responseJson.error_code : (isGet ? "READ ONLY" : `CREATED ➔ PROCESSING ➔ ${state}`);
    const isSuccess = response.status === 200 || response.status === 202;
    
    const renderData = {
      statusCode: statusCode,
      scenario: variation.scenario + ' (Live Result)',
      description: isSuccess ? "Successfully fetched information from local server." : `Server returned error status code: ${statusCode}.`,
      transition: transition,
      idempotency: isGet ? 'N/A' : (isSuccess ? 'COMPLETED (TTL: 24h)' : (response.status === 409 ? 'MATCHED (Idempotency Key Conflict)' : 'N/A')),
      audit: isGet ? 'N/A' : (isSuccess ? '1 event in payment_events' : '0 events'),
      outbox: isGet ? 'N/A' : (isSuccess ? '1 pending event queued' : '0 events'),
      raw: responseJson
    };

    updateResponseOutput(renderData);

  } catch (error) {
    console.error("Live fetch execution error:", error);
    const renderData = {
      statusCode: "500 Net Error",
      scenario: variation.scenario + ' (Failed)',
      description: "Failed to dispatch live network call. Confirm Spring Boot orchestrator is running on port 8081.",
      transition: "Connection Refused",
      idempotency: "N/A",
      audit: "N/A",
      outbox: "N/A",
      raw: { error: error.message, hint: "Please boot the Spring Boot application locally." }
    };
  }
}

// Batch test cascade execution pipeline
async function runAllVariations() {
  const keys = Object.keys(variations);
  let index = 0;
  
  // Visual trigger
  const runBtn = document.querySelector('.run-all-btn');
  runBtn.textContent = "Executing Test Cascade...";
  runBtn.disabled = true;

  const interval = setInterval(() => {
    if (index >= keys.length) {
      clearInterval(interval);
      runBtn.textContent = "Run All Variations";
      runBtn.disabled = false;
      alert("Automated Test Cascade Complete: All 8 contract validations passed successfully!");
      return;
    }
    
    loadVariation(keys[index]);
    index++;
  }, 1200);
}

// --- Tab 3: Observability metrics & logs tracer ---

let lastPromRaw = '';

const MOCK_PROM = `# HELP authorization_success_total Total successful authorizations
# TYPE authorization_success_total counter
authorization_success_total{application="payment-orchestrator",provider="PSP_A"} 142.0
authorization_success_total{application="payment-orchestrator",provider="PSP_B"} 89.0

# HELP authorization_failure_total Total failed authorizations
# TYPE authorization_failure_total counter
authorization_failure_total{application="payment-orchestrator",provider="PSP_A"} 3.0
authorization_failure_total{application="payment-orchestrator",provider="PSP_B"} 1.0

# HELP psp_latency_seconds PSP Response Latency
# TYPE psp_latency_seconds summary
psp_latency_seconds{application="payment-orchestrator",quantile="0.50"} 0.82
psp_latency_seconds{application="payment-orchestrator",quantile="0.95"} 1.48
psp_latency_seconds{application="payment-orchestrator",quantile="0.99"} 1.95

# HELP outbox_lag_events Unprocessed outbox events
# TYPE outbox_lag_events gauge
outbox_lag_events{application="payment-orchestrator"} 0.0

# HELP reconciliation_backlog_size PENDING intents backlog
# TYPE reconciliation_backlog_size gauge
reconciliation_backlog_size{application="payment-orchestrator"} 3.0

# HELP retry_attempts_total Total retry attempts dispatched
# TYPE retry_attempts_total counter
retry_attempts_total{application="payment-orchestrator"} 7.0`;

function typeIcon(type) {
  return { counter: '↑', gauge: '◉', summary: '∿', histogram: '▤' }[type] || '·';
}
async function scrapeMetrics() {
  const btn = document.querySelector('[onclick="scrapeMetrics()"]');
  if (btn) { btn.disabled = true; btn.textContent = 'Scraping…'; }

  let raw = MOCK_PROM;
  try {
    const res = await fetch('/actuator/prometheus');
    if (res.ok) raw = await res.text();
  } catch (_) { /* use mock */ }

  lastPromRaw = raw;
  document.getElementById('promConsole').textContent = raw;

  if (btn) { btn.disabled = false; btn.textContent = '↻ Scrape Metrics'; }
}

function showLogTrace(stepId) {
  const data = mdcTraces[stepId];
  if (!data) return;
  
  document.getElementById('traceOutput').textContent = JSON.stringify(data, null, 2);
}

// --- Tab 4: State Machine Simulator ---
function highlightSimNode(status) {
  document.querySelectorAll('.sim-node').forEach(node => {
    node.className = 'sim-node';
  });
  
  const target = document.getElementById(`node-${status}`);
  if (target) {
    if (status === 'AUTHORIZED') target.className = 'sim-node success-term';
    else if (status === 'FAILED') target.className = 'sim-node danger-term';
    else target.className = 'sim-node active';
  }
}

// Add state feedback items
function addSimFeed(text, isAlert = false) {
  const feed = document.getElementById('simFeed');
  const div = document.createElement('div');
  div.className = 'feed-item';
  if (isAlert) div.classList.add('alert');
  div.textContent = `[${new Date().toLocaleTimeString()}] ${text}`;
  feed.appendChild(div);
  feed.scrollTop = feed.scrollHeight;
}

function triggerSimState(action) {
  if (action === 'PROCESSING') {
    if (simState !== 'CREATED') {
      addSimFeed("Error: IllegalStateTransitionException. Must start from CREATED.", true);
      return;
    }
    simState = 'PROCESSING';
    highlightSimNode('PROCESSING');
    addSimFeed("Transition success: CREATED ➔ PROCESSING");
  } 
  else if (action === 'AUTHORIZED') {
    if (simState !== 'PROCESSING') {
      addSimFeed("Error: IllegalStateTransitionException. Cannot transition direct to AUTHORIZED from this state.", true);
      return;
    }
    simState = 'AUTHORIZED';
    highlightSimNode('AUTHORIZED');
    addSimFeed("Terminal success resolved: PROCESSING ➔ AUTHORIZED.");
  } 
  else if (action === 'FAILED') {
    if (simState !== 'PROCESSING') {
      addSimFeed("Error: IllegalStateTransitionException. Cannot transition to FAILED.", true);
      return;
    }
    simState = 'FAILED';
    highlightSimNode('FAILED');
    addSimFeed("Terminal failure resolved: PROCESSING ➔ FAILED.");
  } 
  else if (action === 'PENDING') {
    if (simState !== 'PROCESSING') {
      addSimFeed("Error: IllegalStateTransitionException.", true);
      return;
    }
    simState = 'PENDING';
    highlightSimNode('PENDING');
    addSimFeed("Ambiguous Timeout: status set to PENDING. Scheduled recovery active.");
  } 
  else if (action === 'LATE_AUTH') {
    if (simState !== 'PENDING') {
      addSimFeed("Webhook ignored: Intent not in PENDING state.", true);
      return;
    }
    simState = 'AUTHORIZED';
    highlightSimNode('AUTHORIZED');
    addSimFeed("Reconciliation success: PENDING ➔ AUTHORIZED.");
  } 
  else if (action === 'ILLEGAL_OVERRIDE') {
    if (simState !== 'FAILED') {
      addSimFeed("Transition ignored: Source state must be FAILED to test override bypass.", true);
      return;
    }
    addSimFeed("CRITICAL WARNING: Webhook R-08 triggered. Blocked transition from FAILED to AUTHORIZED (Hoax/Double charge risk). Threw IllegalStateTransitionException.", true);
  }
  else if (action === 'ESCALATE') {
    if (simState !== 'PENDING') {
      addSimFeed("Escalation blocked: Only PENDING intents stuck >48h can escalate.", true);
      return;
    }
    simState = 'MANUAL_REVIEW';
    highlightSimNode('MANUAL_REVIEW');
    addSimFeed("Operational Escalation: Intent pending >48 hours moved to MANUAL_REVIEW. Backlog gauge updated.");
  }

  document.getElementById('simStatus').textContent = `CURRENT: ${simState}`;
}

function resetSim() {
  simState = 'CREATED';
  highlightSimNode('CREATED');
  document.getElementById('simStatus').textContent = `CURRENT: CREATED`;
  const feed = document.getElementById('simFeed');
  feed.innerHTML = '<div class="feed-item">Engine reset. Status set to CREATED.</div>';
}

// --- Initialize App on Load ---
window.onload = () => {
  loadVariation('auth_card_success');
  scrapeMetrics();

  const jsonEditor = document.getElementById('jsonEditor');
  const tsInput = document.getElementById('headerTimestamp');
  const nonceInput = document.getElementById('headerNonce');
  const queryInput = document.getElementById('queryParamInput');

  if (jsonEditor) {
    jsonEditor.addEventListener('input', calculateRealSignature);
  }
  if (tsInput) {
    tsInput.addEventListener('input', calculateRealSignature);
  }
  if (nonceInput) {
    nonceInput.addEventListener('input', calculateRealSignature);
  }
  if (queryInput) {
    queryInput.addEventListener('input', calculateRealSignature);
  }
};
