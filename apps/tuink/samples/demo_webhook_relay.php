<?php

// Demo: Webhook relay endpoint with signature verification and event logging.
declare(strict_types=1);

final class RequestContext
{
    public function __construct(
        public string $method,
        public string $path,
        public array $headers,
        public string $body,
        public int $receivedAt
    ) {
    }
}

final class WebhookRelay
{
    private array $allowedSources;
    private string $secret;

    public function __construct(array $allowedSources, string $secret)
    {
        $this->allowedSources = $allowedSources;
        $this->secret = $secret;
    }

    public function handle(RequestContext $ctx): array
    {
        if ($ctx->method !== 'POST') {
            return $this->response(405, ['error' => 'method_not_allowed']);
        }

        if ($ctx->path !== '/hooks/events') {
            return $this->response(404, ['error' => 'not_found']);
        }

        $source = $ctx->headers['x-source'] ?? '';
        if (!in_array($source, $this->allowedSources, true)) {
            return $this->response(403, ['error' => 'forbidden_source']);
        }

        $signature = $ctx->headers['x-signature'] ?? '';
        if (!$this->verifySignature($ctx->body, $signature)) {
            return $this->response(401, ['error' => 'bad_signature']);
        }

        $payload = json_decode($ctx->body, true);
        if (!is_array($payload) || !isset($payload['event'])) {
            return $this->response(400, ['error' => 'invalid_payload']);
        }

        $normalized = $this->normalize($payload, $source, $ctx->receivedAt);
        $stored = $this->appendToLog($normalized);

        return $this->response(202, [
            'status' => 'accepted',
            'event_id' => $normalized['event_id'],
            'stored' => $stored,
        ]);
    }

    private function verifySignature(string $body, string $provided): bool
    {
        if ($provided === '') {
            return false;
        }
        $expected = hash_hmac('sha256', $body, $this->secret);
        return hash_equals($expected, $provided);
    }

    private function normalize(array $payload, string $source, int $receivedAt): array
    {
        $eventId = $payload['id'] ?? bin2hex(random_bytes(8));
        $type = (string)($payload['event'] ?? 'unknown');
        $meta = is_array($payload['meta'] ?? null) ? $payload['meta'] : [];

        return [
            'event_id' => (string)$eventId,
            'type' => $type,
            'source' => $source,
            'received_at' => $receivedAt,
            'meta' => $meta,
            'raw' => $payload,
        ];
    }

    private function appendToLog(array $entry): bool
    {
        $line = json_encode($entry, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        if ($line === false) {
            return false;
        }
        $target = __DIR__ . '/webhook-events.log';
        return file_put_contents($target, $line . PHP_EOL, FILE_APPEND | LOCK_EX) !== false;
    }

    private function response(int $status, array $payload): array
    {
        return [
            'status' => $status,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
            'body' => json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ];
    }
}

function collectRequestContext(): RequestContext
{
    $headers = [];
    foreach ($_SERVER as $key => $value) {
        if (str_starts_with($key, 'HTTP_')) {
            $name = strtolower(str_replace('_', '-', substr($key, 5)));
            $headers[$name] = (string)$value;
        }
    }

    return new RequestContext(
        $_SERVER['REQUEST_METHOD'] ?? 'GET',
        parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/',
        $headers,
        file_get_contents('php://input') ?: '',
        time()
    );
}

$relay = new WebhookRelay(['ci', 'billing', 'analytics'], 'dev-secret-change-me');
$result = $relay->handle(collectRequestContext());

http_response_code($result['status']);
foreach ($result['headers'] as $name => $value) {
    header($name . ': ' . $value);
}
echo $result['body'];
