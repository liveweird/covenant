import { describe, expect, test } from "vitest";
import { methodColor, statusClassColor } from "./httpMethods";

describe("httpMethods", () => {
  test("method and status colours follow the app's vocabulary", () => {
    expect(["get", "POST", "put", "PATCH", "delete", "OPTIONS", "custom"].map(methodColor)).toEqual(["teal", "covenant", "yellow", "yellow", "red", "gray", "gray"]);
    expect(["200", "204", "301", "404", "4XX", "500", "default"].map(statusClassColor)).toEqual(["teal", "teal", "gray", "orange", "orange", "red", "gray"]);
  });
});
