import { describe, expect, test } from "vitest";
import { findPointerElement } from "./readerPointer";

describe("findPointerElement", () => {
  test("the exact element, else the nearest rendered ancestor, else nothing; non-pointers match nothing", () => {
    const root = document.createElement("div");
    root.innerHTML = `
      <section data-pointer="/paths/~1pets/get"><div data-pointer="/paths/~1pets/get/responses/200"></div></section>
      <div data-pointer='/a"b'></div>`;
    expect(findPointerElement(root, "/paths/~1pets/get/responses/200")?.getAttribute("data-pointer")).toBe("/paths/~1pets/get/responses/200");
    expect(findPointerElement(root, "/paths/~1pets/get/responses/200/content/schema")?.getAttribute("data-pointer")).toBe("/paths/~1pets/get/responses/200");
    expect(findPointerElement(root, "/paths/~1pets/get/parameters/0")?.getAttribute("data-pointer")).toBe("/paths/~1pets/get");
    expect(findPointerElement(root, "/nowhere")).toBeNull();
    expect(findPointerElement(root, "not a pointer")).toBeNull();
    expect(findPointerElement(root, '/a"b/c')?.getAttribute("data-pointer")).toBe('/a"b');
  });
});
