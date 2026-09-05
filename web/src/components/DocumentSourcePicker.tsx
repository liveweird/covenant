import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, FileButton, Group, Text, TextInput } from "@mantine/core";
import { IconDownload, IconUpload } from "@tabler/icons-react";
import { fetchContractUrl } from "../api/contracts";
import { normalizeSourceUrl } from "../utils/document";
import { FETCH_URL_ERROR_KEYS, saveErrorMessage } from "../utils/saveError";

/**
 * The two ways to load a document besides typing/pasting into the editor: a file from disk,
 * or a public URL fetched SERVER-SIDE (any reachable public host, not just CORS-friendly ones;
 * GitHub/GitLab blob links are rewritten to their raw form first). Hands the text up and
 * remembers where it came from for the hint line.
 */
export default function DocumentSourcePicker({ onLoad, disabled = false }: { onLoad: (text: string, origin: string | null) => void; disabled?: boolean }) {
  const { t } = useTranslation();
  const [url, setUrl] = useState("");
  const [fetching, setFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [fetchedFrom, setFetchedFrom] = useState<string | null>(null);

  async function handleFile(file: File | null) {
    if (!file) return;
    onLoad(await file.text(), file.name);
    setFetchedFrom(null);
  }

  async function handleFetch() {
    setFetching(true);
    setFetchError(null);
    try {
      const normalized = normalizeSourceUrl(url);
      onLoad(await fetchContractUrl(normalized), normalized);
      setFetchedFrom(normalized);
    } catch (err) {
      setFetchError(saveErrorMessage(err, t, FETCH_URL_ERROR_KEYS));
    } finally {
      setFetching(false);
    }
  }

  return (
    <>
      <Group align="flex-end" gap="xs" wrap="wrap">
        <TextInput
          label={t("versions.source.urlLabel")}
          placeholder="https://github.com/acme/orders/blob/main/openapi.yaml"
          value={url}
          onChange={(e) => setUrl(e.currentTarget.value)}
          disabled={disabled}
          style={{ flex: 1, minWidth: 260 }}
        />
        <Button variant="default" leftSection={<IconDownload size={16} />} onClick={() => void handleFetch()} disabled={disabled || !url.trim()} loading={fetching}>
          {t("versions.source.fetch")}
        </Button>
        <FileButton onChange={(file) => void handleFile(file)} accept=".yaml,.yml,.json,text/yaml,application/json" disabled={disabled}>
          {(props) => (
            <Button {...props} variant="default" leftSection={<IconUpload size={16} />}>
              {t("versions.source.pickFile")}
            </Button>
          )}
        </FileButton>
      </Group>
      {fetchError && (
        <Alert color="red" variant="light" title={t("versions.source.fetchFailed")}>
          {fetchError}
        </Alert>
      )}
      {fetchedFrom && (
        <Text size="xs" c="dimmed">
          {t("versions.source.fetchedFrom", { url: fetchedFrom })}
        </Text>
      )}
    </>
  );
}
