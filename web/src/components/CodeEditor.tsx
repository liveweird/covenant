import { useEffect, useRef } from "react";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import { bracketMatching, foldGutter, indentOnInput } from "@codemirror/language";
import { lintGutter, lintKeymap, setDiagnostics } from "@codemirror/lint";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { Compartment, EditorSelection, EditorState } from "@codemirror/state";
import { EditorView, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers, placeholder } from "@codemirror/view";
import type { DocumentFormat } from "../api/versions";
import type { EditorDiagnostic } from "../utils/findingDiagnostics";
import { covenantEditorTheme } from "./codeEditorTheme";

/** A position request: bumping `nonce` re-jumps to the same line (a second click on a finding). */
export type JumpRequest = { line: number; column?: number | null; nonce: number };

export type CodeEditorProps = {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  format: DocumentFormat;
  /** Server findings mapped by utils/findingDiagnostics.ts — pushed with setDiagnostics (the server is the linter). */
  diagnostics?: readonly EditorDiagnostic[];
  jumpTo?: JumpRequest | null;
  /** The accessible name of the editable region. */
  ariaLabel: string;
  placeholder?: string;
  minHeight?: number;
  maxHeight?: number | string;
};

/**
 * The document editor/viewer — CodeMirror 6 over Mantine's theme variables. The ONLY component
 * that imports `@codemirror/*` (its theme sibling aside): the whole editor rides its own lazy
 * `codemirror` chunk through LazyCodeEditor. The React value is the source of truth: an
 * external change (a reset, a copied version) replaces the document; the user's edits flow out
 * through `onChange` without re-entering (the doc already holds them).
 */
export default function CodeEditor({
  value,
  onChange,
  readOnly = false,
  format,
  diagnostics = [],
  jumpTo = null,
  ariaLabel,
  placeholder: placeholderText,
  minHeight = 320,
  maxHeight = "70vh",
}: CodeEditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const language = useRef(new Compartment());
  const editable = useRef(new Compartment());
  const hint = useRef(new Compartment());
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    if (!host.current) return;
    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        foldGutter(),
        drawSelection(),
        history(),
        indentOnInput(),
        bracketMatching(),
        highlightSelectionMatches(),
        lintGutter(),
        keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, ...lintKeymap, indentWithTab]),
        language.current.of(format === "json" ? json() : yaml()),
        editable.current.of([EditorView.editable.of(!readOnly), EditorState.readOnly.of(readOnly)]),
        hint.current.of(placeholderText ? placeholder(placeholderText) : []),
        covenantEditorTheme,
        // tabindex: a READ-ONLY editor drops contenteditable, and its scroller would then be a scroll
        // region with no focusable descendant (axe scrollable-region-focusable) — keyboard users
        // must still be able to reach and scroll the document.
        EditorView.contentAttributes.of({ "aria-label": ariaLabel, "aria-readonly": String(readOnly), tabindex: "0" }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) onChangeRef.current?.(update.state.doc.toString());
        }),
      ],
    });
    const created = new EditorView({ state, parent: host.current });
    view.current = created;
    return () => {
      created.destroy();
      view.current = null;
    };
    // Mount once: every later prop change is applied through the effects below.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the initial state is read once; updates dispatch transactions
  }, []);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const current = v.state.doc.toString();
    if (current !== value) {
      v.dispatch({ changes: { from: 0, to: current.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    view.current?.dispatch({ effects: language.current.reconfigure(format === "json" ? json() : yaml()) });
  }, [format]);

  useEffect(() => {
    view.current?.dispatch({
      effects: editable.current.reconfigure([EditorView.editable.of(!readOnly), EditorState.readOnly.of(readOnly)]),
    });
  }, [readOnly]);

  useEffect(() => {
    view.current?.dispatch({ effects: hint.current.reconfigure(placeholderText ? placeholder(placeholderText) : []) });
  }, [placeholderText]);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const length = v.state.doc.length;
    // The diagnostics were computed against the text the server saw; clamp so a shrunken
    // document never receives an out-of-range mark (CodeMirror throws on it).
    const clamped = diagnostics
      .filter((d) => d.from <= length)
      .map((d) => ({ ...d, to: Math.min(d.to, length) }));
    v.dispatch(setDiagnostics(v.state, clamped));
  }, [diagnostics]);

  useEffect(() => {
    const v = view.current;
    if (!v || !jumpTo) return;
    const lineNumber = Math.min(Math.max(1, jumpTo.line), v.state.doc.lines);
    const line = v.state.doc.line(lineNumber);
    const pos = Math.min(line.from + Math.max(0, (jumpTo.column ?? 1) - 1), line.to);
    v.dispatch({ selection: EditorSelection.cursor(pos), effects: EditorView.scrollIntoView(pos, { y: "center" }), scrollIntoView: true });
    v.focus();
  }, [jumpTo]);

  return <div ref={host} style={{ minHeight, maxHeight, display: "flex", flexDirection: "column" }} data-testid="code-editor" />;
}
