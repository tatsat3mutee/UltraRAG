# API Reference: Limits and Errors

## Authentication
The API uses bearer tokens created under Settings > API tokens. Tokens can be scoped to read-only or read-write access and expire after 90 days unless a different expiry is chosen at creation.

## Rate limits
Starter plans allow 60 requests per minute per token. Team plans allow 600 requests per minute per token. Enterprise limits are set per contract, with a default of 3,000 requests per minute. When a limit is exceeded the API responds with HTTP 429 and a Retry-After header in seconds.

## Pagination
List endpoints return at most 100 items per page. Use the cursor value from the next_cursor field to fetch the following page. Cursors expire after 24 hours.

## Error codes
NW-4011 means the token is missing or malformed. NW-4031 means the token is valid but lacks the required scope. NW-4291 means the rate limit was exceeded; wait for the number of seconds in the Retry-After header before retrying. NW-5003 means a dependent service is temporarily unavailable; retry with exponential backoff starting at 1 second. NW-4092 means a write conflicted with a concurrent update; fetch the latest version of the resource and retry the change.

## Webhooks
Webhook deliveries are signed with an HMAC-SHA256 signature in the X-Northwind-Signature header. Failed deliveries are retried up to 8 times over 24 hours with exponential backoff. After that, the webhook is disabled and the account owner is notified by email.
