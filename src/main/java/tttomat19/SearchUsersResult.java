package tttomat19;

import java.io.Serializable;
import java.util.List;

class SearchUsersResult implements Serializable {

    int total_count;
    boolean incomplete_results;

    static class SearchUserItem implements Serializable {
        String login;
        int id;
        String avatar_url;
        String gravatar_id;
        String url;
        String html_url;
        String followers_url;
        String subscriptions_url;
        String organizations_url;
        String repos_url;
        String received_events_url;
        String type;
        double score;
    }

    List<SearchUserItem> items;

}
