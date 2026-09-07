import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import InferSampleList from "./InferSampleList";
import type { HttpExchangeSample, RelationSample } from "../api/infer";

const HTTP: HttpExchangeSample = { method: "GET", url: "https://api.example.test/orders/42", status: 200 };
const RELATION: RelationSample = { name: "public.users", columns: [{ name: "id", dbType: "int4", nullable: false }] };
const RELATION_ROWS: RelationSample = { name: "public.orders", rows: ['{"id":1}'] };

describe("InferSampleList", () => {
  test("shows the empty caption with no samples", () => {
    renderWithProviders(<InferSampleList type="OPENAPI" samples={[]} onRemove={vi.fn()} />);
    expect(screen.getByText(/No samples yet/)).toBeInTheDocument();
  });

  test("renders an OpenAPI sample as a method badge, the url and the status", () => {
    renderWithProviders(<InferSampleList type="OPENAPI" samples={[HTTP]} onRemove={vi.fn()} />);
    expect(screen.getByText("1 sample")).toBeInTheDocument();
    expect(screen.getByText("GET")).toBeInTheDocument();
    expect(screen.getByText(HTTP.url)).toBeInTheDocument();
    expect(screen.getByText("200")).toBeInTheDocument();
  });

  test("renders an ODCS sample as the relation name and a column count", () => {
    renderWithProviders(<InferSampleList type="ODCS" samples={[RELATION]} onRemove={vi.fn()} />);
    expect(screen.getByText("public.users")).toBeInTheDocument();
    expect(screen.getByText("1 column")).toBeInTheDocument();
  });

  test("renders a pasted ODCS sample (rows, no columns) as a row count", () => {
    renderWithProviders(<InferSampleList type="ODCS" samples={[RELATION_ROWS]} onRemove={vi.fn()} />);
    expect(screen.getByText("public.orders")).toBeInTheDocument();
    expect(screen.getByText("1 row")).toBeInTheDocument();
  });

  test("Remove calls back with the sample's index", async () => {
    const onRemove = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferSampleList type="OPENAPI" samples={[HTTP]} onRemove={onRemove} />);
    await user.click(screen.getByRole("button", { name: `Remove ${HTTP.url}` }));
    expect(onRemove).toHaveBeenCalledWith(0);
  });
});
