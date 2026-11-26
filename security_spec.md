# Security Specification & Red Team Audit (`security_spec.md`)

## 1. Data Invariants
1. **Authentication Gate**: Every read and write operation across `/admins`, `/settings`, `/packages`, and `/licenses` requires an authenticated Firebase user (`request.auth != null`).
2. **Super Admin Authorization**: Administrative mutations (creating/deleting licenses, suspending/reactivating devices, modifying global settings/notifications/promotions, managing packages) are strictly limited to the verified Super Admin (`request.auth.token.email == 'younusali153885@gmail.com' && request.auth.token.email_verified == true`) or a trusted server-seeded `/admins/{uid}` record.
3. **Anti-Scraping License Isolation**: Non-admin users cannot list all licenses (`allow list` requires `isAdmin() || resource.data.activatedByUid == request.auth.uid`). Users may only `get` a specific license by its exact document ID (`/licenses/{licenseId}`) or query licenses they personally activated.
4. **Suspended / Blocked Lock**: Once a license status is `'suspended'` or `'expired'`, non-admin users are strictly blocked from activating or transferring it.
5. **Immutable Audit Metadata**: `createdAt`, `createdBy`, `adminEmail`, and `key` cannot be mutated after creation.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Settings Read**: `unauthDb.collection("settings").doc("global").get()` -> Rejected (`PERMISSION_DENIED`).
2. **Email Spoofing Attack (`email_verified: false`)**: Caller with `{ email: "younusali153885@gmail.com", email_verified: false }` attempts to create a license -> Rejected (`PERMISSION_DENIED`).
3. **Privilege Escalation (`/admins` self-write)**: Regular user attempts to write `/admins/{uid}` -> Rejected (`PERMISSION_DENIED`).
4. **Shadow Update (Ghost Field Injection)**: Admin or user sends `{"isVerified": true}` on `/licenses/{id}` -> Rejected by `hasOnly()` key allowlist.
5. **Cross-User License Scraping**: Regular user calls `db.collection("licenses").get()` without `where("activatedByUid", "==", uid)` -> Rejected (`PERMISSION_DENIED`).
6. **Suspended License Bypass**: User attempts to activate or transfer a license whose `status == "suspended"` -> Rejected (`PERMISSION_DENIED`).
7. **Duration/Price Tampering on Activation**: User activates an `"inactive"` license while modifying `days: 9999` or `price: 0` -> Rejected (`incoming().days == existing().days` & `affectedKeys().hasOnly(...)`).
8. **Identity Spoofing on Activation**: User sets `activatedByUid` to another user's UID -> Rejected (`incoming().activatedByUid == request.auth.uid`).
9. **Future Timestamp Injection**: Client sends `createdAt` or `updatedAt` in the future (`> request.time`) -> Rejected (`incoming().updatedAt <= request.time`).
10. **Denial-of-Wallet Oversized String**: Client sends a 10KB string into `notificationMessage` or `note` -> Rejected by `.size()` bounds.
11. **Path Poisoning**: Document ID with invalid characters or length > 128 -> Rejected by `isValidId()`.
12. **Unfiltered Package List Attack**: Non-admin queries `/packages` without `.where("active", "==", true)` -> Rejected (`existing().active == true`).
