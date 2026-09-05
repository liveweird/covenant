package ch.nokillswit

/** Well-formed and deliberately broken documents per contract type — the validators' and routes' inputs. */
object ContractFixtures {
    val openApi = """
        openapi: 3.1.0
        info:
          title: Petstore
          version: 1.0.0
          description: A minimal OpenAPI 3.1 document.
        servers:
          - url: https://api.example.com/v1
        paths:
          /pets:
            get:
              operationId: listPets
              summary: List pets
              responses:
                "200":
                  description: The pets.
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: "#/components/schemas/Pet"
        components:
          schemas:
            Pet:
              type: object
              required: [id, name]
              properties:
                id: { type: integer }
                name: { type: string }
    """.trimIndent()

    /** A dangling internal ref — swagger-parser flags it. */
    val openApiBrokenRef = """
        openapi: 3.0.3
        info:
          title: Broken
          version: 1.0.0
        paths:
          /things:
            get:
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: "#/components/schemas/Missing"
    """.trimIndent()

    const val openApiJson = """{"openapi":"3.1.0","info":{"title":"J","version":"2.0.0"},"paths":{}}"""

    val asyncApi3 = """
        asyncapi: 3.0.0
        info:
          title: Streetlights
          version: 1.0.0
        servers:
          production:
            host: broker.example.com:9092
            protocol: kafka
        channels:
          lightMeasured:
            address: smartylighting.streetlights.1.0.event.lighting.measured
            messages:
              lightMeasured:
                ${'$'}ref: "#/components/messages/lightMeasured"
        operations:
          receiveLightMeasurement:
            action: receive
            channel:
              ${'$'}ref: "#/channels/lightMeasured"
        components:
          messages:
            lightMeasured:
              name: lightMeasured
              contentType: application/json
              payload:
                type: object
                properties:
                  lumens:
                    type: integer
                    minimum: 0
    """.trimIndent()

    val asyncApi2 = """
        asyncapi: 2.6.0
        info:
          title: Streetlights
          version: 1.0.0
        channels:
          smartylighting/streetlights/1/0/event/lighting/measured:
            publish:
              operationId: receiveLightMeasurement
              message:
                name: lightMeasured
                payload:
                  type: object
                  properties:
                    lumens:
                      type: integer
    """.trimIndent()

    val asyncApiAvro = """
        asyncapi: 3.0.0
        info:
          title: Orders
          version: 2.0.0
        channels:
          orderPlaced:
            address: orders.placed
            messages:
              orderPlaced:
                payload:
                  schemaFormat: application/vnd.apache.avro;version=1.9.0
                  schema:
                    type: record
                    name: OrderPlaced
                    namespace: com.example.orders
                    fields:
                      - name: orderId
                        type: string
        operations:
          onOrderPlaced:
            action: receive
            channel:
              ${'$'}ref: "#/channels/orderPlaced"
    """.trimIndent()

    val asyncApiBadAvro = asyncApiAvro.replace("type: record", "type: recordz")

    /** `channels` must be an object; the payload's JSON Schema has a bogus type — two SCHEMA findings. */
    val asyncApiBroken = """
        asyncapi: 3.0.0
        info:
          title: Broken
          version: 1.0.0
        channels: "not a map"
    """.trimIndent()

    val asyncApiBadPayload = """
        asyncapi: 3.0.0
        info:
          title: Bad payload
          version: 1.0.0
        channels:
          c:
            address: c
            messages:
              m:
                payload:
                  type: 42
        operations: {}
    """.trimIndent()

    val odcs = """
        apiVersion: v3.1.0
        kind: DataContract
        id: 53581432-6c55-4ba2-a65f-72344a91553a
        version: 1.0.0
        status: active
        name: customer_view
        description:
          purpose: A read-only view of active customers.
        schema:
          - name: customer_view
            physicalType: view
            logicalType: object
            properties:
              - name: customer_id
                logicalType: string
                physicalType: uuid
                required: true
                primaryKey: true
                primaryKeyPosition: 1
    """.trimIndent()

    /** `status` outside the enum and a missing `id`. */
    val odcsBroken = """
        apiVersion: v3.1.0
        kind: DataContract
        version: 1.0.0
        status: bogus
        name: broken
    """.trimIndent()

    val odcsOldVersion = odcs.replace("apiVersion: v3.1.0", "apiVersion: v2.2.2")
}
