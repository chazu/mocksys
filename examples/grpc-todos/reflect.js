#!/usr/bin/env node
// Ask a gRPC server what it serves, through server reflection, as `grpcurl list`
// and `grpcurl describe` do. It needs no .proto:
//   node reflect.js <host:port>              # one line per service
//   node reflect.js <host:port> <Service>    # one line per rpc of that service
const path = require("path");
const grpc = require("@grpc/grpc-js");
const loader = require("@grpc/proto-loader");
const { FileDescriptorProto } = require("protobufjs/ext/descriptor");

const [target, service] = process.argv.slice(2);
if (!target) {
  console.error("usage: node reflect.js <host:port> [<Service>]");
  process.exit(2);
}

const proto = path.join(path.dirname(require.resolve("@grpc/reflection")),
  "../proto/grpc/reflection/v1/reflection.proto");
const { ServerReflection } = grpc.loadPackageDefinition(
  loader.loadSync(proto, { keepCase: true, oneofs: true })).grpc.reflection.v1;
const client = new ServerReflection(target, grpc.credentials.createInsecure());
const call = client.ServerReflectionInfo({ deadline: Date.now() + 5000 });

call.on("data", (res) => {
  if (res.error_response) {
    console.error(res.error_response.error_message);
    process.exitCode = 1;
  } else if (res.list_services_response) {
    for (const s of res.list_services_response.service) console.log(s.name);
  } else {
    const name = service.slice(service.lastIndexOf(".") + 1);
    for (const bytes of res.file_descriptor_response.file_descriptor_proto) {
      const file = FileDescriptorProto.decode(bytes);
      const svc = (file.service || []).find((s) => s.name === name);
      for (const m of (svc && svc.method) || []) {
        const stream = (on) => (on ? "stream " : "");
        console.log(`rpc ${m.name} (${stream(m.clientStreaming)}${m.inputType.slice(1)})` +
          ` returns (${stream(m.serverStreaming)}${m.outputType.slice(1)})`);
      }
    }
  }
});
call.on("error", (err) => {
  console.error(`${grpc.status[err.code]}: ${err.details}`);
  process.exit(1);
});
call.on("end", () => client.close());
call.write(service ? { file_containing_symbol: service } : { list_services: "" });
call.end();
