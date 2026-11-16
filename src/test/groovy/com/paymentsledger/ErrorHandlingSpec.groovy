package com.paymentsledger

import com.paymentsledger.api.ErrorHandling
import io.javalin.Javalin
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import spock.lang.Specification

class ErrorHandlingSpec extends Specification {

    def "unexpected exceptions return a generic 500"() {
        given:
        def javalin = Javalin.create { cfg ->
            cfg.startup.showJavalinBanner = false
            ErrorHandling.register(cfg)
            cfg.routes.get("/boom") { ctx -> throw new RuntimeException("secret-detail") }
        }.start(0)

        when:
        def response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:${javalin.port()}/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
        def body = new groovy.json.JsonSlurper().parseText(response.body())

        then:
        response.statusCode() == 500
        body.error == "internal_error"
        !response.body().contains("secret-detail")

        cleanup:
        javalin?.stop()
    }
}
