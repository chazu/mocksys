// Custom verbs for the helpdesk twin. A verb is synchronous and reads time only
// from ctx.now (the virtual clock), so the twin stays deterministic.
const SLA_HOURS = { urgent: 4, high: 24, normal: 72, low: 168 };

module.exports = {
  verbs: {
    // The SLA a new ticket gets from its priority. An unknown priority is the API's
    // own 422, so it ends the request through ctx.respond rather than throwing.
    slaDue({ args, now, respond }) {
      const priority = args.priority || "normal";
      const hours = SLA_HOURS[priority];
      if (hours === undefined) {
        return respond({ status: 422, json: { error: { code: "invalid_priority", message: `unknown priority ${priority}` } } });
      }
      return { priority, dueAt: new Date((now + hours * 3600) * 1000).toISOString() };
    },
  },
};
