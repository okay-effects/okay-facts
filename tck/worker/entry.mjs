// THE TCK'S WORKER (okay-facts, never deployed): the module Scala.js linked, its fetch
import { worker } from "./target/scala-3.3.8/tckworker-fastopt/main.js";
export default { fetch: (request, env, ctx) => worker.fetch(request, env, ctx) };
