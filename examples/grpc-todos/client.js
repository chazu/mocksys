#!/usr/bin/env node
// A minimal grpc-js client for todo.proto:
//   node client.js [--tls] <host:port> <Method> '<json request>'
// --tls connects over TLS, trusting node's roots plus NODE_EXTRA_CA_CERTS, so
// NODE_EXTRA_CA_CERTS="$(mocksys tls ca)" trusts a `tls: true` mock.
// Prints the reply as JSON, or {"code": "<STATUS>", "details": "..."} and exits 1.
const path = require("path");
const grpc = require("@grpc/grpc-js");
const loader = require("@grpc/proto-loader");

const args = process.argv.slice(2);
const tls = args[0] === "--tls";
const [target, method, json = "{}"] = tls ? args.slice(1) : args;
if (!target || !method) {
  console.error("usage: node client.js [--tls] <host:port> <Method> '<json request>'");
  process.exit(2);
}

const def = loader.loadSync(path.join(__dirname, "todo.proto"), {
  keepCase: true, longs: String, enums: String, defaults: true,
});
const { Todos } = grpc.loadPackageDefinition(def).todo.v1;
const client = new Todos(target, tls ? grpc.credentials.createSsl() : grpc.credentials.createInsecure());

client[method](JSON.parse(json), { deadline: Date.now() + 5000 }, (err, reply) => {
  client.close();
  if (err) {
    console.log(JSON.stringify({ code: grpc.status[err.code], details: err.details }));
    process.exit(1);
  }
  console.log(JSON.stringify(reply));
});
