#!/usr/bin/env node
// A minimal grpc-js client for todo.proto:
//   node client.js <host:port> <Method> '<json request>'
// Prints the reply as JSON, or {"code": "<STATUS>", "details": "..."} and exits 1.
const path = require("path");
const grpc = require("@grpc/grpc-js");
const loader = require("@grpc/proto-loader");

const [target, method, json = "{}"] = process.argv.slice(2);
if (!target || !method) {
  console.error("usage: node client.js <host:port> <Method> '<json request>'");
  process.exit(2);
}

const def = loader.loadSync(path.join(__dirname, "todo.proto"), {
  keepCase: true, longs: String, enums: String, defaults: true,
});
const { Todos } = grpc.loadPackageDefinition(def).todo.v1;
const client = new Todos(target, grpc.credentials.createInsecure());

client[method](JSON.parse(json), { deadline: Date.now() + 5000 }, (err, reply) => {
  client.close();
  if (err) {
    console.log(JSON.stringify({ code: grpc.status[err.code], details: err.details }));
    process.exit(1);
  }
  console.log(JSON.stringify(reply));
});
