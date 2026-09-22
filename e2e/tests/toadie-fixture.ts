import { createServer, type Server } from "node:http";
import { randomUUID } from "node:crypto";

// A test-owned HTTP upstream: the browser talks only to Covenant, whose real GraphQL client
// reads this fixture through Docker's host gateway. No live Toadie architecture is modified.
export async function startToadieFixture({ datasets = false }: { datasets?: boolean } = {}) {
  const key = `e2e-${randomUUID()}`;
  let fail = false;
  let revisionNumber = 1;
  let renameAfterServicePage: string | null = null;
  let continuousRevisionChanges = false;
  const requests: { blueprint: string | null; page: number; revision: string }[] = [];
  const blueprints = [
    { id: "1", identifier: "api", relations: {} },
    { id: "2", identifier: "service", relations: {
      provides_apis: { target: "api", many: true }, consumes_apis: { target: "api", many: true },
      system: { target: "system", many: false },
      ...(datasets ? { produces_datasets: { target: "dataset", many: true }, consumes_datasets: { target: "dataset", many: true }, depends_on: { target: "resource", many: true } } : {}),
    } },
    { id: "3", identifier: "system", schema: { properties: { description: { type: "string" } } }, relations: {
      domain: { target: "domain", many: false },
    } },
    { id: "4", identifier: "_team", schema: { properties: { description: { type: "string" } } }, relations: {
      parent: { target: "_team", many: false },
    } },
    { id: "5", identifier: "domain", schema: { properties: { description: { type: "string" } } }, relations: {
      parent_domain: { target: "domain", many: false },
    } },
    ...(datasets ? [
      { id: "6", identifier: "dataset", relations: { stored_in: { target: "resource", many: false } } },
      { id: "7", identifier: "resource", relations: {} },
      { id: "8", identifier: "api_adoption", relations: {
        consumer: { target: "service", many: false }, api: { target: "api", many: false },
      } },
      { id: "9", identifier: "dataset_adoption", relations: {
        consumer: { target: "service", many: false }, dataset: { target: "dataset", many: false },
      } },
    ] : []),
  ];
  const entities = [
    { id: "1", blueprint: "api", identifier: "orders", title: "Orders API", relations: {} },
    { id: "2", blueprint: "api", identifier: "events", title: "Order events", relations: {} },
    { id: "3", blueprint: "service", identifier: "checkout", title: "Checkout service", team: ["retail"],
      relations: { provides_apis: ["orders", "events"], system: "commerce" } },
    { id: "4", blueprint: "service", identifier: "storefront", title: "Storefront website", team: "retail",
      relations: { consumes_apis: ["orders", "events"], system: "commerce" } },
    { id: "5", blueprint: "system", identifier: "commerce", title: "Commerce system", relations: { domain: "commerce" } },
    { id: "6", blueprint: "_team", identifier: "retail", title: "Retail team", relations: {} },
    { id: "7", blueprint: "domain", identifier: "commerce", title: "Commerce domain", relations: { parent_domain: "enterprise" } },
    { id: "8", blueprint: "domain", identifier: "enterprise", title: "Enterprise domain", relations: {} },
    { id: "9", blueprint: "system", identifier: "unassigned", title: "Unassigned system", relations: {} },
    ...(datasets ? [
      { id: "10", blueprint: "dataset", identifier: "orders", title: "Orders dataset", relations: { stored_in: "warehouse" } },
      { id: "11", blueprint: "dataset", identifier: "settlements", title: "Daily settlements", relations: {} },
      { id: "12", blueprint: "service", identifier: "pipeline", title: "Settlement pipeline", team: ["retail"],
        relations: { produces_datasets: ["orders", "settlements"], consumes_datasets: ["orders"], system: "commerce" } },
      { id: "13", blueprint: "service", identifier: "database-only", title: "Database-only service",
        relations: { depends_on: ["warehouse"], system: "commerce" } },
      { id: "14", blueprint: "resource", identifier: "warehouse", title: "Warehouse", relations: {} },
      { id: "15", blueprint: "api_adoption", identifier: "storefront-orders", title: "Declared API adoption",
        properties: { major_line: "1", status: "active" }, relations: { consumer: "storefront", api: "orders" } },
      { id: "16", blueprint: "dataset_adoption", identifier: "pipeline-orders", title: "Declared dataset adoption",
        properties: { contract_version: "1.0.0", status: "active" }, relations: { consumer: "pipeline", dataset: "orders" } },
    ] : []),
  ].map((entity) => ({ team: null, updatedAt: 1, properties: { description: `Description of ${entity.title}` }, ...entity }));
  const server: Server = createServer(async (req, res) => {
    if (req.url !== "/integration/graphql" || req.method !== "POST" || req.headers.authorization !== `Bearer ${key}`) {
      res.writeHead(401).end();
      return;
    }
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const { variables } = JSON.parse(Buffer.concat(chunks).toString()) as {
      variables: { blueprint?: string; page: number; pageSize: number };
    };
    if (fail) {
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ data: null, errors: [{ message: "Unavailable" }] }));
      return;
    }
    const { blueprint, page, pageSize } = variables;
    const rows = blueprint ? entities.filter((entity) => entity.blueprint === blueprint) : blueprints;
    const items = rows.slice((page - 1) * pageSize, page * pageSize);
    const revision = String(revisionNumber);
    requests.push({ blueprint: blueprint ?? null, page, revision });
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
      data: { [blueprint ? "entities" : "blueprints"]: { items, page, pageSize, total: rows.length, revision } },
    }));
    if (renameAfterServicePage && blueprint === "service" && page === 1) {
      const service = entities.find((entity) => entity.blueprint === "service" && entity.identifier === "storefront");
      if (!service) throw new Error("Fixture storefront service is missing");
      service.title = renameAfterServicePage;
      renameAfterServicePage = null;
      revisionNumber += 1;
    } else if (continuousRevisionChanges) {
      revisionNumber += 1;
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "0.0.0.0", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("Fixture did not bind a TCP port");
  return {
    key,
    baseUrl: `http://${process.env.E2E_UPSTREAM_HOST ?? "host.docker.internal"}:${address.port}`,
    browserUrl: `http://localhost:${address.port}`,
    setFailure: () => { fail = true; },
    renameServiceMidScan: (title: string) => {
      revisionNumber += 1;
      renameAfterServicePage = title;
    },
    setContinuousRevisionChanges: () => {
      revisionNumber += 1;
      continuousRevisionChanges = true;
    },
    clearRequests: () => { requests.length = 0; },
    requests: () => [...requests],
    renameEntity: (id: string, title: string) => {
      const entity = entities.find((candidate) => candidate.id === id);
      if (!entity) throw new Error(`Fixture entity ${id} is missing`);
      entity.title = title;
      entity.updatedAt += 1;
      revisionNumber += 1;
    },
    removeEntity: (id: string) => {
      const index = entities.findIndex((entity) => entity.id === id);
      if (index < 0) throw new Error(`Fixture entity ${id} is missing`);
      if (entities[index].blueprint === "_team") {
        entities.forEach((entity) => { entity.team = null; });
      }
      entities.splice(index, 1);
      revisionNumber += 1;
    },
    close: () => new Promise<void>((resolve, reject) => {
      server.closeAllConnections();
      server.close((error) => error ? reject(error) : resolve());
    }),
  };
}
