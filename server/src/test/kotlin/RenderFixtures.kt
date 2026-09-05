package ch.nokillswit

/** Rich documents for the reader's render model — kept apart from the validation fixtures in ContractFixtures. */
object RenderFixtures {
    val openApiRich30 = """
        openapi: 3.0.3
        info:
          title: Pets
          version: 2.1.0
          description: The pet store, richly documented.
          termsOfService: https://example.com/tos
          contact: { name: API team, email: api@example.com }
          license: { name: MIT, url: https://opensource.org/licenses/MIT }
        externalDocs: { url: https://docs.example.com, description: The handbook }
        servers:
          - url: https://{env}.example.com/v2
            description: Per environment
            variables:
              env: { default: api, enum: [api, staging], description: The environment }
        tags:
          - { name: pets, description: Everything about pets }
        security:
          - bearer: []
        paths:
          /pets/{id}:
            summary: One pet
            parameters:
              - { name: id, in: path, schema: { type: integer, minimum: 1 } }
              - ${'$'}ref: "#/components/parameters/Tenant"
            get:
              operationId: getPet
              tags: [pets]
              summary: Read a pet
              deprecated: true
              parameters:
                - { name: id, in: path, required: true, schema: { type: string }, description: overrides the shared one }
                - { name: verbose, in: query, schema: { type: boolean }, style: form, explode: true, example: true }
              responses:
                "200":
                  ${'$'}ref: "#/components/responses/PetFound"
                "404": { description: Not here }
            put:
              operationId: replacePet
              requestBody: { ${'$'}ref: "#/components/requestBodies/NewPet" }
              security: []
              responses:
                default: { description: Anything }
          /untagged:
            post:
              tags: [admin]
              externalDocs: { url: https://docs.example.com/untagged }
              responses:
                "204": { description: done }
        components:
          securitySchemes:
            bearer: { type: http, scheme: bearer, bearerFormat: JWT, description: A JWT }
            oauth:
              type: oauth2
              flows:
                authorizationCode:
                  authorizationUrl: https://auth.example.com/authorize
                  tokenUrl: https://auth.example.com/token
                  scopes: { read: Read things, write: Write things }
          parameters:
            Tenant: { name: X-Tenant, in: header, required: true, schema: { type: string, maxLength: 20 } }
          requestBodies:
            NewPet:
              description: The pet to store
              required: true
              content:
                application/json:
                  schema: { ${'$'}ref: "#/components/schemas/Pet" }
                  examples:
                    rex: { summary: A dog, value: { id: 1, name: Rex } }
          responses:
            PetFound:
              description: The pet
              headers:
                X-Rate-Limit: { description: Remaining calls, schema: { type: integer } }
              content:
                application/json:
                  schema: { ${'$'}ref: "#/components/schemas/Pet" }
          schemas:
            Pet:
              type: object
              title: A pet
              required: [id, name]
              properties:
                id: { type: integer, format: int64, readOnly: true, example: 7 }
                name: { type: string, nullable: true, minLength: 1 }
                score: { type: number, minimum: 0, exclusiveMinimum: true, maximum: 10 }
                kind: { type: string, enum: [dog, cat], default: dog }
                owner: { ${'$'}ref: "#/components/schemas/Owner" }
                friends: { type: array, items: { ${'$'}ref: "#/components/schemas/Pet" } }
                tags: { type: object, additionalProperties: { type: string } }
                extra: { type: object, additionalProperties: false }
                external: { ${'$'}ref: "https://example.com/schemas.yaml#/Thing" }
              discriminator: { propertyName: kind, mapping: { dog: "#/components/schemas/Pet" } }
            Owner:
              type: object
              properties:
                name: { type: string, writeOnly: true, deprecated: true }
                pet: { ${'$'}ref: "#/components/schemas/Pet" }
    """.trimIndent()

    val openApiRich31 = """
        openapi: 3.1.0
        info: { title: Nodes, version: "1", summary: A tree API }
        webhooks:
          nodeChanged:
            post:
              summary: Fired on change
              responses:
                "200": { description: ack }
        paths:
          /nodes:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: array
                        prefixItems: [{ type: string }, { type: [integer, "null"], const: 1 }]
                        items: { ${'$'}ref: "#/components/schemas/Node", description: overlaid }
                        examples: [[a, 1]]
                        exclusiveMinimum: 3
        components:
          schemas:
            Node:
              type: object
              properties:
                value: { type: [string, "null"] }
                children: { type: array, items: { ${'$'}ref: "#/components/schemas/Node" } }
                left: { ${'$'}ref: "#/components/schemas/Node" }
    """.trimIndent()

    val asyncApi2Rich = """
        asyncapi: 2.6.0
        info: { title: Lights, version: 1.0.0 }
        defaultContentType: application/json
        servers:
          prod: { url: kafka://broker.example.com:9092/prod, protocol: kafka, protocolVersion: "3.6", security: [{ sasl: [] }] }
        channels:
          lights/{id}/measured:
            description: Measurements
            parameters:
              id: { description: The light, schema: { type: string } }
            publish:
              operationId: onMeasured
              summary: A light measured
              message:
                oneOf:
                  - ${'$'}ref: "#/components/messages/lightMeasured"
                  - name: turnedOff
                    payload: { type: object, properties: { at: { type: string, format: date-time } } }
            subscribe:
              message:
                title: Dim command
                payload: { type: integer }
        components:
          securitySchemes:
            sasl: { type: scramSha256 }
          messages:
            lightMeasured:
              name: lightMeasured
              headers: { type: object, properties: { correlationId: { type: string } } }
              correlationId: { location: ${'$'}message.header#/correlationId }
              payload: { type: object, properties: { lumens: { type: integer } } }
              examples:
                - { name: bright, payload: { lumens: 900 } }
    """.trimIndent()

    val asyncApi3Rich = """
        asyncapi: 3.0.0
        info:
          title: Orders
          version: 2.0.0
          tags: [{ name: orders }]
          externalDocs: { url: https://docs.example.com/orders }
        servers:
          prod:
            host: broker.example.com:9092
            pathname: /orders
            protocol: kafka
            security: [{ ${'$'}ref: "#/components/securitySchemes/sasl" }]
            bindings: { kafka: {} }
        channels:
          orderPlaced:
            address: orders.placed
            servers: [{ ${'$'}ref: "#/servers/prod" }]
            messages:
              orderPlaced: { ${'$'}ref: "#/components/messages/orderPlaced" }
              inlineNote: { payload: { type: string }, contentType: text/plain }
          orderReply: { address: orders.reply }
          proto:
            address: protos
            messages:
              raw:
                payload:
                  schemaFormat: application/vnd.google.protobuf;version=3
                  schema: "message Raw { string id = 1; }"
        operations:
          receiveOrder:
            action: receive
            channel: { ${'$'}ref: "#/channels/orderPlaced" }
            messages:
              - ${'$'}ref: "#/channels/orderPlaced/messages/orderPlaced"
              - ${'$'}ref: "#/channels/orderPlaced/messages/inlineNote"
            reply: { channel: { ${'$'}ref: "#/channels/orderReply" } }
            tags: [{ name: orders }]
        components:
          securitySchemes:
            sasl: { type: scramSha256 }
          messages:
            orderPlaced:
              name: orderPlaced
              contentType: application/json
              payload:
                schemaFormat: application/vnd.apache.avro;version=1.9.0
                schema:
                  type: record
                  name: OrderPlaced
                  namespace: com.example
                  doc: An order
                  fields:
                    - { name: orderId, type: string, doc: The id }
                    - { name: note, type: ["null", string], default: null }
                    - { name: status, type: { type: enum, name: Status, symbols: [NEW, PAID] } }
                    - { name: lines, type: { type: array, items: { type: record, name: Line, fields: [{ name: sku, type: string }] } } }
                    - { name: attrs, type: { type: map, values: long } }
                    - { name: parent, type: ["null", OrderPlaced] }
                    - { name: hash, type: { type: fixed, name: Hash, size: 16 } }
                    - { name: when, type: { type: long, logicalType: timestamp-millis } }
                    - { name: either, type: [int, string] }
          schemas:
            Money: { type: object, properties: { amount: { type: number } } }
    """.trimIndent()

    val odcsRich = """
        apiVersion: v3.1.0
        kind: DataContract
        id: 7b1b6f2e-0d2c-4a8e-9a1b-4a0e0c1d2e3f
        version: 1.2.0
        status: active
        name: customers
        domain: sales
        dataProduct: crm
        tenant: acme
        tags: [pii, gold]
        contractCreatedTs: "2026-01-01T00:00:00Z"
        description:
          purpose: Who buys.
          limitations: EU only.
          usage: Analytics.
          authoritativeDefinitions: [{ type: businessDefinition, url: https://wiki.example.com/customers }]
          customProperties: [{ property: steward, value: alice }]
        schema:
          - name: customers
            physicalName: crm.customers
            physicalType: table
            logicalType: object
            businessName: Customers
            description: One row per customer.
            dataGranularityDescription: Per customer
            tags: [core]
            quality:
              - { type: library, rule: rowCount, mustBeGreaterThan: 100, dimension: completeness, severity: error }
            properties:
              - name: id
                logicalType: string
                logicalTypeOptions: { format: uuid }
                physicalType: uuid
                required: true
                unique: true
                primaryKey: true
                primaryKeyPosition: 1
                classification: internal
                criticalDataElement: true
                examples: [8d1b...]
                quality: [{ type: text, description: Never null }]
              - name: address
                logicalType: object
                properties:
                  - { name: city, logicalType: string, partitioned: true, partitionKeyPosition: 1 }
                  - name: lines
                    logicalType: array
                    items: { logicalType: string, encryptedName: lines_enc }
              - name: total
                logicalType: number
                transformSourceObjects: [orders]
                transformLogic: SUM(orders.amount)
                customProperties: [{ property: unit, value: EUR }]
        servers:
          - server: prod
            type: postgres
            environment: production
            host: db.example.com
            port: 5432
            database: crm
            roles: [{ role: reader, access: read }]
        team:
          - { username: alice, role: owner, dateIn: "2025-01-01" }
        roles:
          - { role: analyst, access: read, firstLevelApprovers: alice }
        slaDefaultElement: updated_at
        slaProperties:
          - { property: latency, value: 4, unit: h, element: updated_at, driver: operational }
        support:
          - { channel: "#crm", tool: slack, url: https://slack.example.com, scope: interactive }
        price: { priceAmount: 9.95, priceCurrency: USD, priceUnit: megabyte }
        authoritativeDefinitions: [{ type: privacy-statement, url: https://example.com/privacy }]
        customProperties: [{ property: refreshCadence, value: daily }]
    """.trimIndent()
}
