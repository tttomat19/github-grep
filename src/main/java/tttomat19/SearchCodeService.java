package tttomat19;

import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.GitHubRequest;
import org.eclipse.egit.github.core.service.GitHubService;

public class SearchCodeService extends GitHubService {

    @Override
    public GitHubRequest createRequest() {
        return super.createRequest();
    }

    public SearchCodeService(GitHubClient client) {
        super(client);
    }

}

