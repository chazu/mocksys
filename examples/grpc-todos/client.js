#!/usr/bin/env node
// A minimal grpc-js client for todo.proto:
//   node client.js [--tls] <host:port> <Method> '<json request>'
// --tls connects over TLS, trusting node's roots plus NODE_EXTRA_CA_CERTS, so
// NODE_EXTRA_CA_CERTS="$(mocksys tls ca)" trusts a `tls: true` mock.
// Prints the reply as JSON, or {"code": "<STATUS>", "details": "..."} and exits 1.
// A client or bidirectional stream takes a JSON array, one element per message sent.
// A server or bidirectional stream prints one JSON line per message it receives.
const path = require("path");
const grpc = require("@grpc/grpc-js");
const loader = require("@grpc/proto-loader");

const args = process.argv.slice(2);
const tls = args[0] === "--tls";
const [target, method, json] = tls ? args.slice(1) : args;
if (!target || !method) {
  console.error("usage: node client.js [--tls] <host:port> <Method> '<json request>'");
  process.exit(2);
}

const def = loader.loadSync(path.join(__dirname, "todo.proto"), {
  keepCase: true, longs: String, enums: String, defaults: true,
});
const { Todos } = grpc.loadPackageDefinition(def).todo.v1;
const rpc = Todos.service[method];
if (!rpc) {
  console.error(`no rpc ${method} in todo.v1.Todos (${Object.keys(Todos.service).join(", ")})`);
  process.exit(2);
}
const request = JSON.parse(json || (rpc.requestStream ? "[]" : "{}"));
const client = new Todos(target, tls ? grpc.credentials.createSsl() : grpc.credentials.createInsecure());
const options = { deadline: Date.now() + 5000 };

function fail(err) {
  client.close();
  console.log(JSON.stringify({ code: grpc.status[err.code], details: err.details }));
  process.exit(1);
}
function reply(err, msg) {
  if (err) return fail(err);
  client.close();
  console.log(JSON.stringify(msg));
}

let call;
if (rpc.requestStream && rpc.responseStream) call = client[method](options);
else if (rpc.requestStream) call = client[method](options, reply);
else if (rpc.responseStream) call = client[method](request, options);
else call = client[method](request, options, reply);

if (rpc.responseStream) {
  call.on("data", (msg) => console.log(JSON.stringify(msg)));
  call.on("error", fail);
  call.on("end", () => client.close());
}
if (rpc.requestStream) {
  for (const msg of request) call.write(msg);
  call.end();
}
