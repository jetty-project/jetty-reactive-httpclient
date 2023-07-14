![GitHub CI](https://github.com/jetty-project/jetty-reactive-httpclient/workflows/GitHub%20CI/badge.svg)

# Jetty ReactiveStream HttpClient

A [ReactiveStreams](http://www.reactive-streams.org/) wrapper around 
[Jetty](https://eclipse.org/jetty)'s 
[HttpClient](https://www.eclipse.org/jetty/documentation/jetty-11/programming-guide/index.html#pg-client-http).

## Versions

Jetty ReactiveStream HttpClient Versions | Min Java Version | Jetty Version | Status |
---- | ---- | ---- | ----
`1.1.x` | Java 8 | Jetty 9.4.x | End of Community Support (see [#153](https://github.com/jetty-project/jetty-reactive-httpclient/issues/153))
`2.0.x` | Java 11 | Jetty 10.0.x | Stable
`3.0.x` | Java 11 | Jetty 11.0.x | Stable

## Usage

### Plain ReactiveStreams Usage

```java
// Create and start Jetty's HttpClient.
HttpClient httpClient = new HttpClient();
client.start();

// Create a request using the HttpClient APIs.
Request request = httpClient.newRequest("http://localhost:8080/path");

// Wrap the request using the API provided by this project.
ReactiveRequest reactiveRequest = ReactiveRequest.newBuilder(request).build();

// Obtain a ReactiveStream Publisher for the response, discarding the response content.
Publisher<ReactiveResponse> publisher = reactiveRequest.response(ReactiveResponse.Content.discard());

// Subscribe to the Publisher to send the request.
publisher.subscribe(new Subscriber<ReactiveResponse>() {
    @Override
    public void onSubscribe(Subscription subscription) {
        // This is where the request is actually sent.
        subscription.request(1);
    }

    @Override
    public void onNext(ReactiveResponse response) {
        // Use the response
    }

    @Override
    public void onError(Throwable failure) {
    }

    @Override
    public void onComplete() {
    }
});
```

### RxJava 2 Usage

```java
// Create and start Jetty's HttpClient.
HttpClient httpClient = new HttpClient();
client.start();

// Create a request using the HttpClient APIs.
Request request = httpClient.newRequest("http://localhost:8080/path");

// Wrap the request using the API provided by this project.
ReactiveRequest reactiveRequest = ReactiveRequest.newBuilder(request).build();

// Obtain a ReactiveStreams Publisher for the response, discarding the response content.
Publisher<ReactiveResponse> publisher = reactiveRequest.response(ReactiveResponse.Content.discard());

// Wrap the ReactiveStreams Publisher with RxJava.
int status = Single.fromPublisher(publisher)
        .map(ReactiveResponse::getStatus)
        .blockingGet();
```

### Response Content Processing

The response content is processed by passing a `BiFunction` to `ReactiveRequest.response()`.

The `BiFunction` takes as parameters the `ReactiveResponse` and a `Publisher` for the response
content, and must return a `Publisher` of items of type `T` that is the result of the response
content processing.

Built-in utility functions can be found in `ReactiveResponse.Content`.

#### Example: discarding the response content

```java
Publisher<ReactiveResponse> response = request.response(ReactiveResponse.Content.discard());
```

#### Example: converting the response content to a String

```java
Publisher<String> string = request.response(ReactiveResponse.Content.asString());
```

Alternatively, you can write your own processing `BiFunction` using any
ReactiveStreams library, such as RxJava 2 (which provides class `Flowable`):

#### Example: discarding non 200 OK response content

```java
Publisher<Content.Chunk> publisher = reactiveRequest.response((reactiveResponse, contentPublisher) -> {
    if (reactiveResponse.getStatus() == HttpStatus.OK_200) {
        // Return the response content itself.
        return contentPublisher;
    } else {
        // Discard the response content.
        return Flowable.fromPublisher(contentPublisher)
                .filter(chunk -> {
                    // Tell HttpClient that you are done with this chunk.
                    chunk.release();
                    // Discard this chunk.
                    return false;
                });
    }
});
```

Then the response content (if any) can be further processed:

```java
Single<Long> contentLength = Flowable.fromPublisher(publisher)
        .map(chunk -> {
            // Tell HttpClient that you are done with this chunk.
            chunk.release();
            // Return the number of bytes of this chunk.
            return chunk.remaining();
        })
        // Sum the bytes of the chunks.
        .reduce(0L, Long::sum);
```

### Providing Request Content

Request content can be provided in a ReactiveStreams way, through the `ReactiveRequest.Content`
class, which _is-a_ `Publisher` with the additional specification of the content length
and the content type.
Below you can find an example using the utility methods in `ReactiveRequest.Content`
to create request content from a String:

```java
HttpClient httpClient = ...;

String text = "content";
ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, "http://localhost:8080/path")
        .content(ReactiveRequest.Content.fromString(text, "text/plain", StandardCharsets.UTF_8))
        .build();
```

Below another example of creating request content from another `Publisher`:

```java
HttpClient httpClient = ...;

// The Publisher of request content.
Publisher<T> publisher = ...;

// Transform items of type T into ByteBuffer chunks.
Charset charset = StandardCharsets.UTF_8;
Flowable<Content.Chunk> chunks = Flowable.fromPublisher(publisher)
        .map((T t) -> toJSON(t))
        .map((String json) -> json.getBytes(charset))
        .map((byte[] bytes) -> ByteBuffer.wrap(bytes))
        .map(byteBuffer -> Content.Chunk.from(byteBuffer, false));

ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, "http://localhost:8080/path")
        .content(ReactiveRequest.Content.fromPublisher(chunks, "application/json", charset))
        .build();
```

### Events

If you are interested in the request and/or response events that are emitted
by the Jetty HttpClient APIs, you can obtain a `Publisher` for request and/or
response events, and subscribe a listener to be notified of the events.

The event `Publisher`s are "hot" producers and do no buffer events.
If you subscribe to an event `Publisher` after the events have started, the 
`Subscriber` will not be notified of events that already happened, and will
be notified of any event that will happen.

```java
HttpClient httpClient = ...;

ReactiveRequest request = ReactiveRequest.newBuilder(httpClient, "http://localhost:8080/path").build();
Publisher<ReactiveRequest.Event> requestEvents = request.requestEvents();

// Subscribe to the request events before sending the request.
requestEvents.subscribe(new Subscriber<ReactiveRequest.Event>() {
    ...
});

// Similarly for response events.
Publisher<ReactiveResponse.Event> responseEvents = request.responseEvents();

// Subscribe to the response events before sending the request.
responseEvents.subscribe(new Subscriber<ReactiveResponse.Event>() {
    ...
});

// Send the request.
ReactiveResponse response = Single.fromPublisher(request.response(ReactiveResponse.Content.discard()))
        .blockingGet();
```
