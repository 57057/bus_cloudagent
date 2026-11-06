import java.net.*;
import java.net.http.*;
import java.time.Duration;

public class Health {
  public static void main(String[] args) throws Exception {
    var r =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/health"))
                    .timeout(Duration.ofSeconds(3))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200 || !r.body().contains("\"healthy\":true")) System.exit(1);
  }
}
