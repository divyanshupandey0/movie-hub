# Castle TV API Probe Discrepancy Investigation & Audit Report

**Date:** 2026-11-18  
**Provider Name:** Castle TV (`castle_tv` / `cs_castletv`)  
**Target Host:** `api.hlowb.com`  
**Current Operational Status:** **DISABLED / BLOCKED** (`isEnabled = false`)

---

## Executive Summary

When opening the root host `https://api.hlowb.com/` directly in a browser or HTTP client, the server returns an **HTTP 404 Not Found** error. However, a prior automated audit reported **HTTP 200 in 432ms**.

This investigation analyzed both requests, identified the exact difference in tested routes and HTTP methods, and evaluated whether the 432ms response represented genuine API functionality or simple server reachability.

The findings confirm:
1. The **432ms probe did NOT test the root endpoint (`/`)**. It queried a specific subsystem route: `/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US`.
2. The root route (`/`) is unmapped by the remote backend router, returning **HTTP 404**.
3. The 200 OK probe result was **merely server/endpoint reachability for a security handshake token**, returning an opaque encrypted payload (`{"code":200,"msg":"OK","data":"ZkpBVG0qa2dmSg=="}`).
4. **No legitimate media catalog, search, details, or stream extraction endpoints are publicly accessible or functional.**
5. In accordance with safety and compliance policies, **Castle TV remains strictly disabled** in the Movie Flix / Movie Hub application.

---

## 1. Exact URL and HTTP Method in Previous 432ms Probe

- **HTTP Method:** `GET`
- **Full Requested URL:**
  ```
  https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US
  ```
- **Request Headers:**
  ```http
  User-Agent: Mozilla/5.0 (Linux; Android 14)
  ```
- **Latency Recorded:** `432ms` (verified live: ~148ms – 432ms depending on network conditions)

---

## 2. Comparison: Root Endpoint vs. Handshake Endpoint

| Attribute | User Manual Probe | Previous Automated Audit Probe |
| :--- | :--- | :--- |
| **URL** | `https://api.hlowb.com/` | `https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US` |
| **HTTP Method** | `GET` | `GET` |
| **HTTP Status Code** | **`404 Not Found`** | **`200 OK`** |
| **Endpoint Category** | Root landing path (`/`) | System security key exchange |
| **Content-Type** | `application/json` | `application/json;charset=UTF-8` |
| **Response Body** | `{"timestamp":"...","path":"/","status":404,"error":"Not Found","message":null,"requestId":"..."}` | `{"code":200,"msg":"OK","data":"ZkpBVG0qa2dmSg=="}` |
| **Cause of Discrepancy**| The backend application router has no handler mapped for `/`. | The backend router has an active route handler for `/v0.1/system/getSecurityKey/1`. |

---

## 3. Response Details & Sanitized Body

### Handshake Probe Response (`/v0.1/system/getSecurityKey/1...`)
- **HTTP Status Code:** `200 OK`
- **Content-Type:** `application/json` (or `application/json;charset=UTF-8`)
- **Sanitized Response Body:**
  ```json
  {
    "code": 200,
    "msg": "OK",
    "data": "ZkpBVG0qa2dmSg=="
  }
  ```

### Root Probe Response (`/`)
- **HTTP Status Code:** `404 Not Found`
- **Content-Type:** `application/json`
- **Sanitized Response Body:**
  ```json
  {
    "timestamp": "2026-11-18T07:45:46.089+00:00",
    "path": "/",
    "status": 404,
    "error": "Not Found",
    "message": null,
    "requestId": "4b2da832-3636857504"
  }
  ```

---

## 4. Assessment: Genuine API Response vs. Server Reachability

The 432ms probe was **merely server network reachability and a basic token handshake**, **NOT** a genuine, working media API response:

1. **No Content Catalog or Media Data:**
   - The response only delivered a base64 encoded token (`"ZkpBVG0qa2dmSg=="`, decoding to proprietary key bytes `fJATm*kgfJ`).
   - It did not return any media metadata, playable stream URLs, or catalog search results.
2. **Proprietary Encryption & Private Token Gate:**
   - Castle TV utilizes proprietary encrypted payload communications and requires private API keys and tokens not publicly documented or authorized.
   - Without private reverse-engineered decryption routines or authorized access keys, media queries cannot be executed.
3. **Provider Implementation Behavior:**
   - In `CastleTvProvider.kt`, `checkHealth()` maps this state to `ProviderStatus.DEGRADED` / restricted:
     ```kotlin
     ProviderHealth(
         status = ProviderStatus.DEGRADED,
         latencyMs = latency,
         errorMessage = "API active but requires private encryption key and proprietary payload deciphering"
     )
     ```
   - All functional media routines safely return empty results to prevent fabricating fake links or bypassing encryption:
     ```kotlin
     override suspend fun search(query: String): List<MediaItem> = emptyList()
     override suspend fun getDetails(mediaId: String): MediaDetail? = null
     override suspend fun getStreamItems(...): List<StreamItem> = emptyList()
     ```

---

## 5. Security and Compliance Rules Followed

- **No Guessed Endpoints:** Only the documented endpoints (`/` and `/v0.1/system/getSecurityKey/1...`) were evaluated.
- **No Authentication Bypass:** No unauthorized keys, token spoofing, or private cipher seeds were injected.
- **Public & Authorized Scope:** Only public network reachability and status diagnostics were conducted.
- **Strict Disablement:** Castle TV is explicitly disabled by default (`isEnabled = false`) in both the native provider and the CloudStream adapter registry (`cs_castletv = BLOCKED`).

---

## 6. Updated Castle TV Audit Entry

| Field | Updated Audit Value |
| :--- | :--- |
| **Provider** | Castle TV (`com.euthopiar.core.provider.castle.CastleTvProvider`) |
| **Ecosystem Adapter** | Castle TV CloudStream Adapter (`cs_castletv`) |
| **Health Status** | **RESTRICTED / DEGRADED** (Server reachable, but media access gated) |
| **Root Endpoint Status**| `HTTP 404 Not Found` (`https://api.hlowb.com/`) |
| **Handshake Endpoint**| `HTTP 200 OK` in ~148–432ms (`https://api.hlowb.com/v0.1/system/getSecurityKey/1...`) |
| **Working Search/Extract**| **NO (0% working, requires private authorization)** |
| **Operational State** | **DISABLED (`isEnabled = false`)** |
| **Recommendation** | Keep disabled until legitimate, public, and authorized API documentation is made available. |
