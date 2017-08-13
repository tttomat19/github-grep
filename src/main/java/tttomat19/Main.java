package tttomat19;

import com.google.gson.reflect.TypeToken;
import io.reactivex.Emitter;
import io.reactivex.Flowable;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.GitHubRequest;
import org.eclipse.egit.github.core.client.GitHubResponse;
import org.eclipse.egit.github.core.client.RequestException;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.eclipse.egit.github.core.client.PagedRequest.PAGE_FIRST;
import static org.eclipse.egit.github.core.client.PagedRequest.PAGE_SIZE;

public class Main {

    static class RateLimitResetAwareGitHubClient extends GitHubClient {
        long resetEpochSecond = 0;

        @Override
        protected GitHubClient updateRateLimits(HttpURLConnection request) {
            resetEpochSecond = request.getHeaderFieldLong("X-RateLimit-Reset", 0);
            return super.updateRateLimits(request);
        }

        public long getResetUtcEpoch() {
            return resetEpochSecond;
        }

        public void throttleABit() throws InterruptedException {
            Thread.sleep(1500);
        }

        public RateLimitResetAwareGitHubClient waitUntilRateLimitResetIfStuck() throws InterruptedException {
            if (getRemainingRequests() != 0) { // not stuck
                return this;
            }
            long nowEpochSecond = Instant.now().getEpochSecond();

            long sleepFor = (resetEpochSecond - nowEpochSecond);
            System.out.println("sleeping for " + sleepFor + " seconds, due to rate limit");
            Thread.sleep(Math.max(1, sleepFor * 1000));
            return this;
        }

        @Override
        protected HttpURLConnection createGet(String uri) throws IOException {
            HttpURLConnection httpURLConnection = super.createGet(uri);
            httpURLConnection.setReadTimeout(30000);
            httpURLConnection.setConnectTimeout(30000);
            return httpURLConnection;
        }

    }

    static HashSet<String> loadVisistedSet(Path path) throws IOException {
        // whatever
        if (Files.notExists(path)) {
            Files.createFile(path);
            return new HashSet<>();
        }

        List<String> visitedList = Files.readAllLines(path);

        // skip first (empty line) and last (probably not completely processed) lines
        List<String> completelyProcessedList = visitedList.subList(1, visitedList.size() - 1);

        HashSet<String> visited = new HashSet<>();
        visited.addAll(completelyProcessedList);

        return visited;
    }

    static Integer handleRequestException(RequestException e, Emitter<?> emitter, Integer page) throws InterruptedException, RequestException {
        if (e.getStatus() == 422) {
            System.err.println(e.formatErrors());
            emitter.onComplete();
            return null;
        }
        if (e.getMessage().contains("abuse")) {
            System.err.println("abuse detected, sleeping for 15 minutes");
            Thread.sleep(TimeUnit.MINUTES.toMillis(15));
            return page;
        }
        throw e;
    }


    public static void main(String[] args) throws IOException {
        final Properties properties = new Properties();
        properties.load(new FileReader("application.properties"));

        RateLimitResetAwareGitHubClient client = new RateLimitResetAwareGitHubClient();
        client.setHeaderAccept("application/vnd.github.v3.text-match+json");

        client.setOAuth2Token(properties.getProperty("github-api.token"));

        SearchCodeService searchCode = new SearchCodeService(client);

        Path usersPagePath = Paths.get("users_page");
        if (!Files.exists(usersPagePath)) {
            Files.createFile(usersPagePath);
        }
        int usersProcessedPage = Integer.parseInt(
                Files.readAllLines(usersPagePath).get(0)
        );

        Flowable<SearchUsersResult> usersPages = Flowable.generate(
                () -> usersProcessedPage,
                (Integer page, Emitter<SearchUsersResult> emitter) -> {
                    System.out.println("emit users");

                    Files.write(usersPagePath, page.toString().getBytes());

                    GitHubRequest searchJavaUsersRequest = searchCode.createRequest();

                    searchJavaUsersRequest.setUri(new StringBuilder()
                            .append("/search/users?")
                            .append("q=language:java")
                            .append("&sort=followers")
                            .append("&page=").append(page)
                            .append("&per_page=").append(PAGE_SIZE)
                    );

                    searchJavaUsersRequest.setType(new TypeToken<SearchUsersResult>() {
                    }.getType());

                    System.out.println(searchJavaUsersRequest.toString());

                    client.throttleABit();
                    client.waitUntilRateLimitResetIfStuck();
                    GitHubResponse searchJavaReposResponse;
                    try {
                        searchJavaReposResponse = client.get(searchJavaUsersRequest);
                    } catch (SocketTimeoutException e) {
                        System.err.println(e.toString());
                        return page;
                    } catch (RequestException e) {
                        return handleRequestException(e, emitter, page);
                    }

                    SearchUsersResult searchUsersResult = (SearchUsersResult) searchJavaReposResponse.getBody();
                    System.out.println("searchUsersResult.incomplete_results: " + searchUsersResult.incomplete_results);

                    emitter.onNext(searchUsersResult);

                    if (PAGE_SIZE * page >= searchUsersResult.total_count) {
                        emitter.onComplete();
                        return null;
                    }

                    return page + 1;
                }
        );

        Flowable<SearchUsersResult.SearchUserItem> users = usersPages
                .flatMap(searchUsersResult ->
                        Flowable.fromIterable(searchUsersResult.items)
                );

        Path visitedUsersPath = Paths.get("visited_users");
        HashSet<String> visitedUsers = loadVisistedSet(visitedUsersPath);

        Flowable<SearchUsersResult.SearchUserItem> nonVisitedUsers = users
                .filter(searchUserItem -> visitedUsers.contains(searchUserItem.html_url))
                .doOnNext(searchUserItem -> {
                    visitedUsers.add(searchUserItem.html_url);
                    Files.write(visitedUsersPath, ("\n" + searchUserItem.html_url).getBytes(), StandardOpenOption.APPEND);
                });

        Flowable<SearchCodeResult> springBootProjectsPages = nonVisitedUsers.flatMap(searchUserItem -> Flowable.generate(
                () -> PAGE_FIRST,

                (Integer page, Emitter<SearchCodeResult> emitter) -> {

                    GitHubRequest searchSpringBootProjectsRequest = searchCode.createRequest();

                    searchSpringBootProjectsRequest.setUri(new StringBuilder()
                            .append("/search/code?")
                            .append("q=spring-boot-starter-parent")
                            .append("+")
                            .append("in:file")
                            .append("+")
                            .append("filename:pom.xml")
                            .append("+")
                            .append("extension:xml")
                            .append("+")
                            .append("user:").append(searchUserItem.login)
                            .append("&")
                            .append("page=").append(page)
                            .append("&")
                            .append("per_page=").append(PAGE_SIZE)
                    );

                    searchSpringBootProjectsRequest.setType(new TypeToken<SearchCodeResult>() {
                    }.getType());

                    System.out.println(searchSpringBootProjectsRequest.toString());

                    client.throttleABit();
                    client.waitUntilRateLimitResetIfStuck();
                    GitHubResponse searchSpringBootProjectsResponse;
                    try {
                        searchSpringBootProjectsResponse = client.get(searchSpringBootProjectsRequest);
                    } catch (SocketTimeoutException e) {
                        System.err.println(e.toString());
                        return page;
                    } catch (RequestException e) {
                        return handleRequestException(e, emitter, page);
                    }

                    SearchCodeResult springBootProjectsPage = (SearchCodeResult) searchSpringBootProjectsResponse.getBody();
                    if (springBootProjectsPage.total_count != 0) {
                        System.out.println("found pom.xml using spring-boot: " + springBootProjectsPage.total_count);
                    }

                    emitter.onNext(springBootProjectsPage);

                    if (PAGE_SIZE * page >= springBootProjectsPage.total_count) {
                        emitter.onComplete();
                        return null;
                    }

                    return page + 1;
                }
        ));

        Flowable<Repository> repos = springBootProjectsPages
                .flatMap(searchCodeResult -> Flowable.fromIterable(searchCodeResult.items))
                .map(codeItem -> codeItem.repository);

        Path visitedReposPath = Paths.get("visited_repos");
        HashSet<String> visitedRepos = loadVisistedSet(visitedReposPath);

        Flowable<Repository> nonVisitedRepos = repos
                .filter(repository -> visitedRepos.contains(repository.getHtmlUrl()))
                .doOnNext(repository -> {
                    Files.write(visitedReposPath, ("\n" + repository.getHtmlUrl()).getBytes(), StandardOpenOption.APPEND);
                    visitedRepos.add(repository.getHtmlUrl());
                });

        Flowable<SearchCodeResult> filesPages = nonVisitedRepos.flatMap(repository -> Flowable.generate(
                () -> PAGE_FIRST,
                (Integer page, Emitter<SearchCodeResult> emitter) -> {
                    GitHubRequest searchTestClassesRequest = searchCode.createRequest();
                    searchTestClassesRequest.setUri(new StringBuilder()
                            .append("/search/code?")
                            .append("q=\"junit.Test\"")
                            .append("+")
                            .append("in:file")
                            .append("+")
                            .append("filename:Test")
                            .append("+")
                            .append("extension:java")

                            .append("+")
                            .append("repo:").append(repository.generateId())

                            .append("&")
                            .append("page=").append(page)
                            .append("&")
                            .append("per_page=").append(PAGE_SIZE)
                    );
                    System.out.println(searchTestClassesRequest.toString());

                    searchTestClassesRequest.setType(new TypeToken<SearchCodeResult>() {
                    }.getType());

                    client.throttleABit();
                    client.waitUntilRateLimitResetIfStuck();
                    GitHubResponse searchTestClassesResponse;
                    try {
                        searchTestClassesResponse = client.get(searchTestClassesRequest);
                    } catch (SocketTimeoutException e) {
                        System.err.println(e.toString());
                        return page + 1;
                    } catch (RequestException e) {
                        return handleRequestException(e, emitter, page);
                    }

                    SearchCodeResult searchTestClassesResponsePage = (SearchCodeResult) searchTestClassesResponse.getBody();

                    emitter.onNext(searchTestClassesResponsePage);

                    if (PAGE_SIZE * page >= searchTestClassesResponsePage.total_count) {
                        emitter.onComplete();
                        return null;
                    }

                    return page + 1;
                }
        ));

        Flowable<SearchCodeResult.CodeItem> files = filesPages.flatMap(
                searchCodeResult -> Flowable.fromIterable(searchCodeResult.items)
        );

        Flowable<SearchCodeResult.CodeItem> junitTests = files
                .filter(codeItem -> codeItem.text_matches
                        .stream()
                        .filter(textMatch -> textMatch.property.equals("content"))
                        .anyMatch(textMatch -> textMatch.fragment.contains("junit.Test"))
                );

        Path visitedFilesPath = Paths.get("visited_files");
        HashSet<String> visitedFiles = loadVisistedSet(visitedFilesPath);

        Flowable<SearchCodeResult.CodeItem> nonVisitedTests = junitTests
                .filter(codeItem -> visitedFiles.contains(codeItem.html_url))
                .doOnNext(codeItem -> {
                    visitedFiles.add(codeItem.html_url);
                    Files.write(visitedFilesPath, ("\n" + codeItem.html_url).getBytes(), StandardOpenOption.APPEND);
                });

        nonVisitedTests.subscribe(new Subscriber<SearchCodeResult.CodeItem>() {
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                subscription.request(1);
            }

            @Override
            public void onNext(SearchCodeResult.CodeItem codeItem) {
                System.out.println("found junit test file: " + codeItem.html_url);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onComplete() {
                System.out.println("onComplete");
            }

        });

    }

}
