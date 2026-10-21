// Custom verbs for the webhook receiver in kit.yaml. `mocksys kit webhooks` copies
// this file into the scenario; edit that copy, which mocksys loads again on each
// `mocksys run`. Running the kit again keeps an edited copy unless you pass --force.
const crypto = require("node:crypto");

module.exports = {
  verbs: {
    // GitHub-style X-Hub-Signature-256: "sha256=" + hex HMAC of the raw body.
    verifySignature({ request, args, respond }) {
      const body = request.body || "";
      const expected = "sha256=" + crypto.createHmac("sha256", args.secret).update(body).digest("hex");
      const given = String(args.signature || "");
      const ok = given.length === expected.length &&
        crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
      if (!ok) return respond({ status: 401, json: { error: "signature does not match" } });
      try {
        return JSON.parse(body);
      } catch (e) {
        return respond({ status: 400, json: { error: "body is not JSON" } });
      }
    },
  },
};
