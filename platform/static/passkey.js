// Passkey (WebAuthn) ceremony helpers for the platform dashboard.
//
// Server endpoints speak JSON envelopes:
//   begin  → { sessionToken, options }   (options = PublicKeyCredential*Options)
//   finish ← { sessionToken, credential } (credential = serialized navigator.credentials result)
//
// All binary fields travel as base64url strings.
(function () {
  'use strict';

  // --- base64url ⇄ ArrayBuffer -------------------------------------------

  function b64uToBuf(s) {
    const pad = '='.repeat((4 - (s.length % 4)) % 4);
    const b64 = (s + pad).replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64);
    const buf = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
    return buf.buffer;
  }

  function bufToB64u(buf) {
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  // --- option/result (de)serialization ------------------------------------

  function decodeCreationOptions(options) {
    const pk = options.publicKey;
    pk.challenge = b64uToBuf(pk.challenge);
    pk.user.id = b64uToBuf(pk.user.id);
    (pk.excludeCredentials || []).forEach((c) => { c.id = b64uToBuf(c.id); });
    return pk;
  }

  function decodeRequestOptions(options) {
    const pk = options.publicKey;
    pk.challenge = b64uToBuf(pk.challenge);
    (pk.allowCredentials || []).forEach((c) => { c.id = b64uToBuf(c.id); });
    return pk;
  }

  function encodeAttestation(cred) {
    return {
      id: cred.id,
      rawId: bufToB64u(cred.rawId),
      type: cred.type,
      authenticatorAttachment: cred.authenticatorAttachment || undefined,
      response: {
        attestationObject: bufToB64u(cred.response.attestationObject),
        clientDataJSON: bufToB64u(cred.response.clientDataJSON),
        transports: cred.response.getTransports ? cred.response.getTransports() : undefined,
      },
    };
  }

  function encodeAssertion(cred) {
    return {
      id: cred.id,
      rawId: bufToB64u(cred.rawId),
      type: cred.type,
      authenticatorAttachment: cred.authenticatorAttachment || undefined,
      response: {
        authenticatorData: bufToB64u(cred.response.authenticatorData),
        clientDataJSON: bufToB64u(cred.response.clientDataJSON),
        signature: bufToB64u(cred.response.signature),
        userHandle: cred.response.userHandle ? bufToB64u(cred.response.userHandle) : undefined,
      },
    };
  }

  // --- fetch helpers -------------------------------------------------------

  async function postJSON(url, body) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || ('request failed (' + res.status + ')'));
    return data;
  }

  // --- public API ----------------------------------------------------------

  // Runs a full create() ceremony: begin at beginURL (posting formData so the
  // server can validate + stash it), create the credential, finish at
  // finishURL. Resolves with the finish response JSON.
  async function register(beginURL, finishURL, formData) {
    const begin = await postJSON(beginURL, formData);
    const cred = await navigator.credentials.create({ publicKey: decodeCreationOptions(begin.options) });
    if (!cred) throw new Error('passkey creation was cancelled');
    return postJSON(finishURL, {
      sessionToken: begin.sessionToken,
      credential: encodeAttestation(cred),
    });
  }

  // Runs a full get() ceremony. `mediation` may be 'conditional' for
  // autofill-triggered sign-in; abortSignal cancels a pending conditional
  // request when the user clicks the explicit button instead. `beginBody`
  // (e.g. {email}) restricts the ceremony server-side to one account.
  async function login(beginURL, finishURL, mediation, abortSignal, beginBody) {
    const begin = await postJSON(beginURL, beginBody || {});
    const cred = await navigator.credentials.get({
      publicKey: decodeRequestOptions(begin.options),
      mediation: mediation || undefined,
      signal: abortSignal || undefined,
    });
    if (!cred) throw new Error('sign-in was cancelled');
    return postJSON(finishURL, {
      sessionToken: begin.sessionToken,
      credential: encodeAssertion(cred),
    });
  }

  async function conditionalAvailable() {
    return !!(window.PublicKeyCredential &&
      PublicKeyCredential.isConditionalMediationAvailable &&
      (await PublicKeyCredential.isConditionalMediationAvailable()));
  }

  window.Passkey = { register, login, conditionalAvailable };
})();
