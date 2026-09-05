// The CodeMirror look, expressed in Mantine's CSS variables so one extension serves both
// colour schemes (light-dark() pairs where a token needs its own hue). Only CodeEditor.tsx
// and this file may import @codemirror/* / @lezer/* (eslint no-restricted-imports).
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { EditorView } from "@codemirror/view";
import { tags } from "@lezer/highlight";

const chrome = EditorView.theme({
  "&": {
    fontSize: "13px",
    backgroundColor: "var(--mantine-color-body)",
    color: "var(--mantine-color-text)",
    border: "1px solid light-dark(var(--mantine-color-gray-3), var(--mantine-color-dark-4))",
    borderRadius: "var(--mantine-radius-md)",
    overflow: "hidden",
  },
  "&.cm-focused": {
    outline: "2px solid var(--covenant-accent-ink)",
    outlineOffset: "-1px",
  },
  ".cm-scroller": {
    fontFamily: "var(--mantine-font-family-monospace)",
    lineHeight: "1.55",
  },
  ".cm-content": { padding: "8px 0" },
  ".cm-placeholder": { color: "var(--mantine-color-dimmed)" },
  ".cm-gutters": {
    backgroundColor: "light-dark(var(--mantine-color-gray-0), var(--mantine-color-dark-6))",
    color: "var(--mantine-color-dimmed)",
    borderRight: "1px solid light-dark(var(--mantine-color-gray-2), var(--mantine-color-dark-4))",
  },
  ".cm-activeLine": { backgroundColor: "light-dark(rgba(106, 50, 189, 0.05), rgba(168, 135, 242, 0.08))" },
  ".cm-activeLineGutter": { backgroundColor: "transparent", color: "var(--mantine-color-text)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection": {
    backgroundColor: "light-dark(var(--mantine-color-covenant-1), var(--mantine-color-dark-4)) !important",
  },
  ".cm-cursor": { borderLeftColor: "var(--mantine-color-text)" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--mantine-color-error)" },
  ".cm-lintRange-warning": { backgroundImage: "none", textDecoration: "underline wavy var(--covenant-ink-warning)" },
  ".cm-lintRange-info": { backgroundImage: "none", textDecoration: "underline dotted var(--mantine-color-dimmed)" },
  ".cm-tooltip": {
    backgroundColor: "var(--mantine-color-body)",
    color: "var(--mantine-color-text)",
    border: "1px solid var(--mantine-color-default-border)",
    borderRadius: "var(--mantine-radius-sm)",
    boxShadow: "var(--mantine-shadow-md)",
    fontFamily: "var(--mantine-font-family)",
    fontSize: "12px",
  },
  ".cm-diagnostic": { borderLeftWidth: "3px" },
  ".cm-diagnostic-error": { borderLeftColor: "var(--mantine-color-error)" },
  ".cm-diagnostic-warning": { borderLeftColor: "var(--covenant-ink-warning)" },
  ".cm-diagnostic-info": { borderLeftColor: "var(--mantine-color-dimmed)" },
  ".cm-panels": { backgroundColor: "var(--mantine-color-body)", color: "var(--mantine-color-text)" },
  ".cm-panels input, .cm-panels button": { fontFamily: "var(--mantine-font-family)" },
  ".cm-searchMatch": { backgroundColor: "light-dark(var(--mantine-color-yellow-2), var(--mantine-color-yellow-9))" },
  "&[aria-readonly=true] .cm-content": { caretColor: "transparent" },
});

const highlight = HighlightStyle.define([
  { tag: [tags.propertyName, tags.definition(tags.propertyName)], color: "light-dark(#5b2aa6, #c4adf8)" },
  { tag: tags.string, color: "light-dark(#087255, #63e6be)" },
  { tag: [tags.number, tags.bool, tags.null], color: "light-dark(#b23a0a, #ffc078)" },
  { tag: tags.keyword, color: "light-dark(#1864ab, #74c0fc)" },
  { tag: tags.comment, color: "var(--mantine-color-dimmed)", fontStyle: "italic" },
  { tag: [tags.punctuation, tags.separator], color: "var(--mantine-color-dimmed)" },
  { tag: tags.meta, color: "light-dark(#862e9c, #e599f7)" },
  { tag: tags.invalid, color: "var(--mantine-color-error)" },
]);

export const covenantEditorTheme = [chrome, syntaxHighlighting(highlight)];
