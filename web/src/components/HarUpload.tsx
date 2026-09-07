import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, FileButton, Group, Select, Stack, Text } from "@mantine/core";
import { IconUpload } from "@tabler/icons-react";
import type { HttpExchangeSample } from "../api/infer";
import { readHar, type HarReadResult } from "../utils/har";

const MAX_EXCHANGES_PER_ADD = 50;

/**
 * A DevTools/HAR export, read entirely CLIENT-SIDE (`utils/har.ts` — a full export can carry
 * MiBs of cookies/headers, so nothing but the shape reaches the server): pick a file, pick the
 * origin to keep (defaulting to the one with the most JSON-relevant entries), Add appends up to
 * 50 of that origin's exchanges to the sample list.
 */
export default function HarUpload({ onAdd }: { onAdd: (samples: HttpExchangeSample[]) => void }) {
  const { t } = useTranslation();
  const [result, setResult] = useState<HarReadResult | null>(null);
  const [origin, setOrigin] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [kept, setKept] = useState<number | null>(null);

  async function handleFile(file: File | null) {
    if (!file) return;
    setError(null);
    setKept(null);
    setResult(null);
    setOrigin(null);
    try {
      const text = await file.text();
      const parsed = readHar(text);
      setResult(parsed);
      setOrigin(parsed.origins[0]?.origin ?? null);
    } catch (err) {
      setError(t(err instanceof Error && err.message === "too large" ? "infer.har.tooLarge" : "infer.har.notHar"));
    }
  }

  function handleAdd() {
    if (!result || !origin) return;
    const matching = result.exchanges.filter((e) => e.url.startsWith(`${origin}/`) || e.url === origin);
    const selected = matching.slice(0, MAX_EXCHANGES_PER_ADD);
    onAdd(selected);
    setKept(selected.length);
  }

  return (
    <Stack gap="sm">
      <FileButton onChange={(file) => void handleFile(file)} accept=".har,application/json">
        {(props) => (
          <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>
            {t("infer.har.pick")}
          </Button>
        )}
      </FileButton>
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      {result && (
        <Group align="flex-end" gap="sm" wrap="wrap">
          <Select
            label={t("infer.har.origin")}
            data={result.origins.map((o) => ({ value: o.origin, label: `${o.origin} (${o.count})` }))}
            value={origin}
            onChange={setOrigin}
            allowDeselect={false}
            w={360}
          />
          <Button onClick={handleAdd} variant="default" disabled={!origin}>
            {t("infer.har.add")}
          </Button>
        </Group>
      )}
      {kept != null && result && (
        <Text size="sm" c="dimmed">
          {t("infer.har.kept", { kept, total: result.total })}
        </Text>
      )}
    </Stack>
  );
}
