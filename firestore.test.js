const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_younus_123";
const ADMIN_EMAIL = "younusali153885@gmail.com";
const ALICE_UID = "alice_user_123";
const BOB_UID = "bob_user_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read settings or licenses", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("settings").doc("global").get());
  await assertFails(unauthDb.collection("licenses").doc("ABCD-1234-EFGH-5678").get());
});

test("Email spoofing test: unverified admin email is rejected from creating licenses or updating settings", async () => {
  const spoofAdminDb = testEnv.authenticatedContext("spoof_uid", {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();

  const now = new Date();
  await assertFails(
    spoofAdminDb.collection("licenses").doc("TEST-KEY-0001").set({
      key: "TEST-KEY-0001",
      days: 30,
      price: 60,
      status: "inactive",
      adminEmail: ADMIN_EMAIL,
      createdBy: "spoof_uid",
      maxVideoDuration: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Verified Super Admin: can create license, update settings, and create packages", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  const now = new Date();
  await assertSucceeds(
    adminDb.collection("settings").doc("global").set({
      licenseSystemEnabled: true,
      appName: "GhostDevice",
      supportNumber: "01717775962",
      notificationEnabled: true,
      notificationTitle: "ঈদ স্পেশাল অফার",
      notificationMessage: "সব প্যাকেজে ৫০% ছাড়!",
      promoBadge: "OFFER",
      promoButtonText: "অফার নিন",
      promoActionUrl: "https://t.me/AirdropKoP24/71494",
      setupVideoEnabled: true,
      setupVideoTitle: "GhostDevice Setup Guide",
      setupVideoUrl: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      updatedBy: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    adminDb.collection("licenses").doc("XXXX-YYYY-ZZZZ-1111").set({
      key: "XXXX-YYYY-ZZZZ-1111",
      days: 30,
      price: 60,
      status: "inactive",
      adminEmail: ADMIN_EMAIL,
      createdBy: ADMIN_UID,
      maxVideoDuration: 0,
      note: "VIP Customer",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Regular authenticated user: can activate an inactive license key, but cannot tamper with days or price", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("licenses").doc("LIC-1111-2222-3333").set({
      key: "LIC-1111-2222-3333",
      days: 30,
      price: 60,
      status: "inactive",
      adminEmail: ADMIN_EMAIL,
      createdBy: ADMIN_UID,
      maxVideoDuration: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Attempt tampering with days -> FAILS
  await assertFails(
    aliceDb.collection("licenses").doc("LIC-1111-2222-3333").update({
      status: "active",
      days: 365,
      deviceId: "device_alice_001",
      activatedByUid: ALICE_UID,
      activatedAt: "2026-10-31T01:00:00.000Z",
      expiresAt: "2026-11-30T01:00:00.000Z",
      updatedAt: now,
    })
  );

  // Legitimate activation -> SUCCEEDS
  await assertSucceeds(
    aliceDb.collection("licenses").doc("LIC-1111-2222-3333").update({
      status: "active",
      deviceId: "device_alice_001",
      activatedByUid: ALICE_UID,
      activatedAt: "2026-10-31T01:00:00.000Z",
      expiresAt: "2026-11-30T01:00:00.000Z",
      updatedAt: now,
    })
  );
});

test("Suspended license cannot be activated or transferred by user", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("licenses").doc("LIC-SUSP-9999").set({
      key: "LIC-SUSP-9999",
      days: 30,
      price: 60,
      status: "suspended",
      deviceId: "device_alice_001",
      activatedByUid: ALICE_UID,
      activatedAt: "2026-10-31T01:00:00.000Z",
      expiresAt: "2026-11-30T01:00:00.000Z",
      adminEmail: ADMIN_EMAIL,
      createdBy: ADMIN_UID,
      maxVideoDuration: 0,
      createdAt: now,
      updatedAt: now,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("licenses").doc("LIC-SUSP-9999").update({
      deviceId: "device_bob_002",
      activatedByUid: BOB_UID,
      lastTransferredAt: "2026-10-31T02:00:00.000Z",
      updatedAt: now,
    })
  );
});

test("Regular user cannot scrape all licenses without activatedByUid filter", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("licenses").get());
  await assertSucceeds(
    aliceDb.collection("licenses").where("activatedByUid", "==", ALICE_UID).get()
  );
});
