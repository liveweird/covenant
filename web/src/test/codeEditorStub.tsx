/* eslint-disable react-refresh/only-export-components */
// -- test scaffolding: a stand-in for components/CodeEditor.tsx under happy-dom, which cannot
// lay CodeMirror out. Honours the same props: a labelled textarea carries the value and edits,
// the diagnostics render as a count so page tests can assert the wiring without the editor.
import type { CodeEditorProps } from "../components/CodeEditor";

export default function CodeEditorStub({ value, onChange, readOnly, ariaLabel, diagnostics = [], jumpTo, placeholder }: CodeEditorProps) {
  return (
    <div data-testid="code-editor-stub" data-diagnostics={diagnostics.length} data-jump={jumpTo ? `${jumpTo.line}:${jumpTo.column ?? 1}` : ""}>
      <textarea aria-label={ariaLabel} value={value} readOnly={readOnly} placeholder={placeholder} onChange={(e) => onChange?.(e.currentTarget.value)} />
    </div>
  );
}

/** The `vi.mock` factory page tests pass: `vi.mock("../components/LazyCodeEditor", codeEditorMock)`. */
export function codeEditorMock() {
  return { default: CodeEditorStub };
}
