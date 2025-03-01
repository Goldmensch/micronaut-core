/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.core.convert.format.Format
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Issue
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.annotation.Nullable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.function.Consumer

/**
 * @author Graeme Rocher
 * @since 1.0
 */
@MicronautTest
@Property(name = 'spec.name', value = 'HttpGetSpec')
@Property(name = 'micronaut.http.client.read-timeout', value = '30s')
class HttpGetSpec extends Specification {

    @Inject
    @Client("/")
    @AutoCleanup
    HttpClient client

    @Inject
    MyGetClient myGetClient

    @Inject
    MyGetHelper myGetHelper

    @Inject
    OverrideUrlClient overrideUrlClient

    @Inject
    EmbeddedServer embeddedServer

    void "test simple get request"() {
        when:
        Flux flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/simple").header("Accept-Encoding", "gzip")
        ))
        Optional<String> body = flowable.map({ res ->
            res.getBody(String)}
        ).blockFirst()

        then:
        body.isPresent()
        body.get() == 'success'
    }

    void "test simple get with Publisher<Void> return"() {
        JavaClient javaClient = embeddedServer.applicationContext.getBean(JavaClient)

        when:
        javaClient.subscribe(javaClient.simple())

        then:
        noExceptionThrown()
    }

    void "test simple 404 request"() {
        when:
        Flux<?> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/doesntexist")
        ))

        flowable.blockFirst()

        then:
        def e = thrown(HttpClientResponseException)
        e.response.getBody(Map).get()._embedded.errors[0].message == "Page Not Found"
        e.status == HttpStatus.NOT_FOUND

    }

    void "test 500 request with body"() {
        when:
        Flux.from(client.exchange(
                HttpRequest.GET("/get/error"), Argument.of(String), Argument.of(String)
        )).blockFirst()

        then:
        HttpClientResponseException e = thrown()
        e.message == "Server error"
        e.status == HttpStatus.INTERNAL_SERVER_ERROR
        e.response.getBody(String).get() == "Server error"

    }

    void "test 500 request with json body"() {
        when:
        Flux.from(client.exchange(
                HttpRequest.GET("/get/jsonError"), Argument.of(String), Argument.of(Map)
        )).blockFirst()

        then:
        HttpClientResponseException e = thrown()
        e.message == "{foo=bar}"
        e.status == HttpStatus.INTERNAL_SERVER_ERROR

    }

    void "test simple 404 request as VndError"() {
        when:
        HttpResponse<?> response = Flux.from(client.exchange(
                HttpRequest.GET("/get/doesntexist")
        )).onErrorResume({ error ->
            if (error instanceof HttpClientResponseException) {
                return Flux.just(HttpResponse.status(error.status).body(error.response.getBody(Map).orElse(null)))
            }
            throw error
        }).blockFirst()

        def body = response.body

        then:
        body.isPresent()
        body.get()._embedded.errors[0].message == "Page Not Found"
    }

    void "test simple blocking get request"() {
        given:
        BlockingHttpClient client = this.client.toBlocking()

        when:
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/get/simple"),
                String
        )

        def body = response.getBody()

        then:
        body.isPresent()
        body.get() == 'success'
    }

    void "test simple get request with type"() {
        when:
        Flux<HttpResponse<String>> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/simple"), String
        ))
        HttpResponse<String> response = flowable.blockFirst()
        Optional<String> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        body.isPresent()
        body.get() == 'success'
    }

    void "test simple exchange request with POJO"() {
        when:
        Flux<HttpResponse<Book>> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/pojo"), Book
        ))

        HttpResponse<Book> response = flowable.blockFirst()
        Optional<Book> body = response.getBody()
        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.status == HttpStatus.OK
        body.isPresent()
        body.get().title == 'The Stand'
        response.getBody(String.class).get() == '{"title":"The Stand"}'
        response.getBody(byte[].class).get().length > 0
    }

    void "test simple exchange request with POJO with String response"() {
        when:
        Flux<HttpResponse<Book>> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/pojo"), String
        ))

        HttpResponse<String> response = flowable.blockFirst()
        Optional<String> body = response.getBody()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.status == HttpStatus.OK
        body.isPresent()
        response.getBody(String.class).get() == '{"title":"The Stand"}'
        response.getBody(Book.class).get().title == 'The Stand'
        response.getBody(byte[].class).get().length > 0
    }

    void "test simple retrieve request with POJO"() {
        when:
        Flux<Book> flowable = Flux.from(client.retrieve(
                HttpRequest.GET("/get/pojo"), Book
        ))

        Book book = flowable.blockFirst()

        then:
        book != null
        book.title == "The Stand"
    }

    void "test simple get request with POJO list"() {
        when:
        Flux<HttpResponse<List<Book>>> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/pojoList"), Argument.of(List, Book)
        ))

        HttpResponse<List<Book>> response = flowable.blockFirst()
        Optional<List<Book>> body = response.getBody()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.status == HttpStatus.OK
        body.isPresent()


        when:
        List<Book> list = body.get()

        then:
        list.size() == 1
        list.get(0) instanceof Book
        list.get(0).title == 'The Stand'
    }

    void "test get with @Client"() {
        expect:
        myGetHelper.simple() == "success"
        myGetHelper.simpleSlash() == "success"
        myGetHelper.simplePreceedingSlash() == "success"
        myGetHelper.simpleDoubleSlash() == "success"
        myGetHelper.queryParam() == "a!b"
    }

    void "test query parameter with @Client interface"() {
        expect:
        myGetClient.queryParam('{"service":["test"]}') == '{"service":["test"]}'
        myGetClient.queryParam('foo', 'bar') == 'foo-bar'
        myGetClient.queryParam('foo%', 'bar') == 'foo%-bar'
    }

    void "test body availability"() {
        when:
        Flux<HttpResponse> flowable = client.exchange(
                HttpRequest.GET("/get/simple")
        )
        String body
        flowable.next().subscribe((Consumer){ HttpResponse res ->
            Thread.sleep(3000)
            body = res.getBody(String).orElse(null)
        })
        def conditions = new PollingConditions(timeout: 4)

        then:
        conditions.eventually {
            assert body == 'success'
        }
    }

    void "test blocking body availability"() {
        given:
        BlockingHttpClient client = client.toBlocking()

        when:
        HttpResponse res = client.exchange(
                HttpRequest.GET("/get/simple")
        )
        String body = res.getBody(String).orElse(null)

        then:
        body == null
    }

    void "test that Optional.empty() should return 404"() {
        when:
        Flux<?> flowable = Flux.from(client.exchange(
                HttpRequest.GET("/get/empty")
        ))

        HttpResponse<Optional<String>> response = flowable.blockFirst()

        then:
        def e = thrown(HttpClientResponseException)
        e.response.getBody(Map).get()._embedded.errors[0].message == "Page Not Found"
        e.status == HttpStatus.NOT_FOUND
    }

    void "test a non empty optional should return the value"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.GET("/get/notEmpty"), String
        )


        then:
        body == "not empty"
    }

    void 'test format dates with @Format'() {
        given:
        MyGetClient client = this.myGetClient
        Date d = new Date(2018, 10, 20)
        LocalDate dt = LocalDate.now()

        expect:
        client.formatDate(d) == d.toString()
        client.formatDateQuery(d) == d.toString()
        client.formatDateTime(dt) == dt.toString()
        client.formatDateTimeQuery(dt) == dt.toString()
    }

    void "test controller slash concatenation"() {
        given:
        BlockingHttpClient client = this.client.toBlocking()

        expect:
        client.retrieve("/noslash/slash") == "slash"
        client.retrieve("/noslash/slash/") == "slash"
        client.retrieve("/noslash/noslash") == "noslash"
        client.retrieve("/noslash/noslash/") == "noslash"
        client.retrieve("/noslash/startslash") == "startslash"
        client.retrieve("/noslash/startslash/") == "startslash"
        client.retrieve("/noslash/endslash") == "endslash"
        client.retrieve("/noslash/endslash/") == "endslash"

        client.retrieve("/slash/slash") == "slash"
        client.retrieve("/slash/slash/") == "slash"
        client.retrieve("/slash/noslash") == "noslash"
        client.retrieve("/slash/noslash/") == "noslash"
        client.retrieve("/slash/startslash") == "startslash"
        client.retrieve("/slash/startslash/") == "startslash"
        client.retrieve("/slash/endslash") == "endslash"
        client.retrieve("/slash/endslash/") == "endslash"

        client.retrieve("/ending-slash/slash") == "slash"
        client.retrieve("/ending-slash/slash/") == "slash"
        client.retrieve("/ending-slash/noslash") == "noslash"
        client.retrieve("/ending-slash/noslash/") == "noslash"
        client.retrieve("/ending-slash/startslash") == "startslash"
        client.retrieve("/ending-slash/startslash/") == "startslash"
        client.retrieve("/ending-slash/endslash") == "endslash"
        client.retrieve("/ending-slash/endslash/") == "endslash"

        client.retrieve("/noslash") == "noslash"
        client.retrieve("/noslash/") == "noslash"
        client.retrieve("/slash") == "slash"
        client.retrieve("/slash/") == "slash"
        client.retrieve("/startslash") == "startslash"
        client.retrieve("/startslash/") == "startslash"
        client.retrieve("/endslash") == "endslash"
        client.retrieve("/endslash/") == "endslash"
    }

    void "test a request with a custom host header"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.GET("/get/host").header("Host", "http://foo.com"), String
        )

        then:
        body == "http://foo.com"
    }

    void "test empty list returns ok"() {
        when:
        HttpResponse response = client.toBlocking().exchange(HttpRequest.GET("/get/emptyList"), Argument.listOf(Book))

        then:
        noExceptionThrown()
        response.status == HttpStatus.OK
        response.body().isEmpty()
    }

    void "test single empty list returns ok"() {
        when:
        HttpResponse response = client.toBlocking().exchange(HttpRequest.GET("/get/emptyList/single"), Argument.listOf(Book))

        then:
        noExceptionThrown()
        response.status == HttpStatus.OK
        response.body().isEmpty()
    }

    void "test setting query params on the request"() {
        when:
        MutableHttpRequest request = HttpRequest.GET("/get/multipleQueryParam?foo=x")
        request.parameters.add('bar', 'y')
        String body = client.toBlocking().retrieve(request)

        then:
        noExceptionThrown()
        body == 'x-y'
    }

    void "test overriding the URL"() {
        def client = this.overrideUrlClient

        when:
        String val = client.overrideUrl(embeddedServer.getURL().toString())

        then:
        val == "success"
    }

    void "test multiple uris"() {
        def client = this.myGetClient

        when:
        String val = client.multiple()

        then:
        val == "multiple mappings"

        when:
        val = client.multipleMappings()

        then:
        val == "multiple mappings"
    }

    void "test exploded query param request URI"() {
        when:
        MyGetClient client = this.myGetClient
        String requestUri = client.queryParamExploded(["abc", "xyz"])

        then:
        requestUri.endsWith("bar=abc&bar=xyz")
    }

    void "test exploded query param request URI 2"() {
        when:
        MyGetClient client = this.myGetClient
        String requestUri = client.queryParamExploded2(["abc", "xyz"])

        then:
        requestUri.endsWith("bar=abc&bar=xyz")
    }

    void "test multiple exploded query param request URI"() {
        when:
        MyGetClient client = this.myGetClient
        String requestUri = client.multipleExplodedQueryParams(["abc", "xyz"], "random")

        then:
        requestUri.endsWith("bar=abc&bar=xyz&tag=random")
    }

    void "test multiple exploded query param request URI 2"() {
        when:
        MyGetClient client = this.myGetClient
        String requestUri = client.multipleExplodedQueryParams2(["abc", "xyz"], "random")

        then:
        requestUri.endsWith("bar=abc&bar=xyz&tag=random")
    }

    @Issue("https://github.com/micronaut-projects/micronaut-core/issues/2782")
    void "test single letter uri"() {
        given:
        HttpClient client = HttpClient.create(embeddedServer.getURL())

        when:
        MutableHttpRequest request = HttpRequest.GET("/get/a")
        String body = client.toBlocking().retrieve(request)

        then:
        noExceptionThrown()
        body == 'success'

        cleanup:
        client.close()
    }

    void "test creating a client with a null URL"() {
        given:
        BlockingHttpClient client = HttpClient.create(null).toBlocking()

        when:
        String uri = UriBuilder.of(embeddedServer.getURI()).path("/get/simple").toString()
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET(uri),
                String
        )
        def body = response.getBody()

        then:
        body.isPresent()
        body.get() == 'success'
    }

    void "test creating an rx client with a null URL"() {
        given:
        BlockingHttpClient client = HttpClient.create(null).toBlocking()

        when:
        String uri = UriBuilder.of(embeddedServer.getURI()).path("/get/simple").toString()
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET(uri),
                String
        )
        def body = response.getBody()

        then:
        body.isPresent()
        body.get() == 'success'

        cleanup:
        client.close()
    }

    void "test a nested list"() {
        when:
        List<List<Book>> books = myGetClient.nestedPojoList()

        then:
        books[0][0] instanceof Book
        books[0][0].title == "The Stand"

        when:
        BlockingHttpClient client = HttpClient.create(embeddedServer.getURL()).toBlocking()
        books = client.retrieve(HttpRequest.GET("/get/nestedPojoList"), Argument.listOf(Argument.listOf(Book.class)))

        then:
        books[0][0] instanceof Book
        books[0][0].title == "The Stand"

        cleanup:
        client.close()
    }

    void "test an invalid content type"() {
        when:
        myGetClient.invalidContentType()

        then:
        def ex = thrown(HttpClientResponseException)
        ex.message == "Client '/get': Failed to decode the body for the given content type [does/notexist]"
    }

    @Issue("https://github.com/micronaut-projects/micronaut-core/issues/5223")
    void "format query param declared in URI"() {
        given:
        MyGetClient client = this.myGetClient
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        LocalDateTime dt = LocalDateTime.now()

        expect:
        client.formatUriDeclaredQueryParam(dt) == formatter.format(dt)
    }

    void "test an invalid content type reactive response"() {
        when:
        Mono.from(myGetClient.invalidContentTypeReactive()).block()

        then:
        HttpClientResponseException ex = thrown()
        ex.message == "Client '/get': Failed to decode the body for the given content type [does/notexist]"
    }

    void "test deserializing map wrapped by Reactive type"() {
        when:
        def response = Mono.from(myGetClient.reactiveMap()).block()
        def book1 = response.get("key1")

        then:
        book1 instanceof Book
        book1.title == "title1"
    }

    @Issue("https://github.com/micronaut-projects/micronaut-core/issues/6358")
    void "test a controller returning null for Response<Publisher<?>>"() {
        when:
        HttpResponse response = client.toBlocking().exchange(HttpRequest.GET("/get/nullPublisher"))

        then:
        noExceptionThrown()
        response.status == HttpStatus.OK
        response.body() == null
    }

    @Issue("https://github.com/micronaut-projects/micronaut-core/issues/4735")
    void "test a declarative client that returns a publisher and no content response"() {
        when:
        def publisher = myGetClient.noContentPublisher()

        then:
        publisher != null
        Mono.from(publisher).block() == null
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Controller("/get")
    static class GetController {

        @Get(value = "a", produces = MediaType.TEXT_PLAIN)
        String a() {
            return "success"
        }

        @Get(value = "/simple", produces = MediaType.TEXT_PLAIN)
        String simple() {
            return "success"
        }

        @Get("/pojo")
        Book pojo() {
            return new Book(title: "The Stand")
        }

        @Get("/pojoList")
        List<Book> pojoList() {
            return [ new Book(title: "The Stand") ]
        }

        @Get("/nestedPojoList")
        List<List<Book>> nestedPojoList() {
            return [[ new Book(title: "The Stand") ]]
        }

        @Get("/emptyList")
        List<Book> emptyList() {
            return []
        }

        @Get("/emptyList/single")
        @SingleResult
        Publisher<List<Book>> emptyListSingle() {
            return Mono.just([])
        }

        @Get(value = "/error", produces = MediaType.TEXT_PLAIN)
        HttpResponse error() {
            return HttpResponse.serverError().body("Server error")
        }

        @Get("/jsonError")
        HttpResponse jsonError() {
            return HttpResponse.serverError().body([foo: "bar"])
        }

        @Get("/queryParam")
        String queryParam(@QueryValue String foo) {
            return foo
        }

        @Get("/queryParamExploded{?bar*}")
        String queryParamExploded(@QueryValue("bar") List<String> foo, HttpRequest<?> request) {
            return request.getUri().toString()
        }

        @Get("/multipleExplodedQueryParams{?bar*,tag}")
        String multipleExplodedQueryParams(@QueryValue("bar") List<String> foo, @Nullable @QueryValue("tag") String label, HttpRequest<?> request) {
            return request.getUri().toString()
        }

        @Get("/multipleQueryParam")
        String queryParam(@QueryValue String foo, @QueryValue String bar) {
            return foo + '-' + bar
        }

        @Get("/empty")
        Optional<String> empty() {
            return Optional.empty()
        }

        @Get("/notEmpty")
        Optional<String> notEmpty() {
            return Optional.of("not empty")
        }

        @Get("/date/{myDate}")
        String formatDate(@Format('yyyy-MM-dd') Date myDate) {
            return myDate.toString()
        }

        @Get("/dateTime/{myDate}")
        String formatDateTime(@Format('yyyy-MM-dd') LocalDate myDate) {
            return myDate.toString()
        }

        @Get("/dateQuery")
        String formatDateQuery(@QueryValue @Format('yyyy-MM-dd') Date myDate) {
            return myDate.toString()
        }

        @Get("/dateTimeQuery")
        String formatDateTimeQuery(@QueryValue @Format('yyyy-MM-dd') LocalDate myDate) {
            return myDate.toString()
        }

        @Get("/formatUriDeclaredQueryParam")
        String formatUriDeclaredQueryParam(@QueryValue @Format("MMMM dd yyyy 'at' h:m a") LocalDateTime time) {
            return time.toString();
        }

        @Get("/host")
        String hostHeader(@Header String host) {
            return host
        }

        @Get(uris = ["/multiple", "/multiple/mappings"])
        String multipleMappings() {
            return "multiple mappings"
        }

        @Get(value = "/invalidContentType", produces = "does/notexist")
        String invalidContentType() {
            return "hello"
        }

        @Get("/reactiveMap")
        @SingleResult
        Publisher<Map<String, Book>> reactiveMap() {
            def map = ["key1": new Book(title: "title1"), "key2": new Book(title: "title2")]
            return Mono.just(map)
        }

        @Get(value = "/nestedPublishers")
        Publisher<HttpResponse<Publisher<String>>> nestedPublishers() {
            return Publishers.just(HttpResponse.ok(Publishers.just("abc")))
        }

        @Get(value = "/nullPublisher")
        HttpResponse<Publisher<String>> nullPublisher() {
            return HttpResponse.ok()
        }

        @Get("/noContentPublisher")
        HttpResponse noContent() {
            return HttpResponse.noContent()
        }
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Controller("noslash")
    static class NoSlashController {

        @Get("/slash/")
        String slash() {
            "slash"
        }

        @Get("noslash")
        String noSlash() {
            "noslash"
        }

        @Get("/startslash")
        String startSlash() {
            "startslash"
        }

        @Get("endslash/")
        String endSlash() {
            "endslash"
        }
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Controller("/slash")
    static class SlashController {

        @Get("/slash/")
        String slash() {
            "slash"
        }

        @Get("noslash")
        String noSlash() {
            "noslash"
        }

        @Get("/startslash")
        String startSlash() {
            "startslash"
        }

        @Get("endslash/")
        String endSlash() {
            "endslash"
        }
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Controller("/ending-slash/")
    static class EndingSlashController {

        @Get("/slash/")
        String slash() {
            "slash"
        }

        @Get("noslash")
        String noSlash() {
            "noslash"
        }

        @Get("/startslash")
        String startSlash() {
            "startslash"
        }

        @Get("endslash/")
        String endSlash() {
            "endslash"
        }
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Controller
    static class SlashRootController {

        @Get("/slash/")
        String slash() {
            "slash"
        }

        @Get("noslash")
        String noSlash() {
            "noslash"
        }

        @Get("/startslash")
        String startSlash() {
            "startslash"
        }

        @Get("endslash/")
        String endSlash() {
            "endslash"
        }
    }

    static class Book {
        String title
    }

    static class Error {
        String message
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Client("/get")
    static interface MyGetClient {

        @Get(value = "/simple", produces = MediaType.TEXT_PLAIN)
        String simple()

        @Get("/pojo")
        Book pojo()

        @Get("/pojoList")
        List<Book> pojoList()

        @Get("/nestedPojoList")
        List<List<Book>> nestedPojoList()

        @Get(value = "/error", produces = MediaType.TEXT_PLAIN)
        HttpResponse error()

        @Get("/jsonError")
        HttpResponse jsonError()

        @Get("/queryParam")
        String queryParam(@QueryValue String foo)

        @Get("/queryParamExploded{?bar*}")
        String queryParamExploded(@QueryValue("bar") List<String> foo)

        @Get("/queryParamExploded{?bar*}")
        String queryParamExploded2(@QueryValue List<String> bar)

        @Get("/multipleExplodedQueryParams{?bar*,tag}")
        String multipleExplodedQueryParams(@QueryValue("bar") List<String> foo, @QueryValue("tag") String label)

        @Get("/multipleExplodedQueryParams{?bar*,tag}")
        String multipleExplodedQueryParams2(@QueryValue List<String> bar, @QueryValue String tag)

        @Get("/multipleQueryParam")
        String queryParam(@QueryValue String foo, @QueryValue String bar)

        @Get("/date/{myDate}")
        String formatDate(@Format('yyyy-MM-dd') Date myDate)

        @Get("/dateTime/{myDate}")
        String formatDateTime(@Format('yyyy-MM-dd') LocalDate myDate)

        @Get("/dateQuery")
        String formatDateQuery(@QueryValue @Format('yyyy-MM-dd') Date myDate)

        @Get("/dateTimeQuery")
        String formatDateTimeQuery(@QueryValue @Format('yyyy-MM-dd') LocalDate myDate)

        @Get("/multiple")
        String multiple()

        @Get("/multiple/mappings")
        String multipleMappings()

        @Get(value = "/invalidContentType", consumes = "does/notexist")
        Book invalidContentType()

        @Get(value = "/formatUriDeclaredQueryParam{?time}")
        String formatUriDeclaredQueryParam(@QueryValue @Format("MMMM dd yyyy 'at' h:m a") LocalDateTime time);

        @Get(value = "/invalidContentType", consumes = "does/notexist")
        @SingleResult
        Publisher<Book> invalidContentTypeReactive()

        @Get("/reactiveMap")
        @SingleResult
        Publisher<Map<String, Book>> reactiveMap()

        @Get(value = "/noContentPublisher")
        Publisher<String> noContentPublisher()
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @Client("http://not.used")
    static interface OverrideUrlClient {

        @Get(value = "{+url}/get/simple", consumes = MediaType.TEXT_PLAIN)
        String overrideUrl(String url);
    }

    @Requires(property = 'spec.name', value = 'HttpGetSpec')
    @jakarta.inject.Singleton
    static class MyGetHelper {

        private final StreamingHttpClient rxClientSlash
        private final StreamingHttpClient rxClient

        MyGetHelper(@Client("/get/") StreamingHttpClient rxClientSlash,
                    @Client("/get") StreamingHttpClient rxClient) {
            this.rxClient = rxClient
            this.rxClientSlash = rxClientSlash
        }

        String simple() {
            rxClient.toBlocking().exchange(HttpRequest.GET("simple"), String).body()
        }

        String simplePreceedingSlash() {
            rxClient.toBlocking().exchange(HttpRequest.GET("/simple"), String).body()
        }

        String simpleSlash() {
            rxClientSlash.toBlocking().exchange(HttpRequest.GET("simple"), String).body()
        }

        String simpleDoubleSlash() {
            rxClientSlash.toBlocking().exchange(HttpRequest.GET("/simple"), String).body()
        }

        String queryParam() {
            rxClient.toBlocking().exchange(HttpRequest.GET("/queryParam?foo=a!b"), String).body()
        }
    }
}
