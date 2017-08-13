package tttomat19;

import org.eclipse.egit.github.core.Repository;

import java.io.Serializable;
import java.util.List;

class SearchCodeResult implements Serializable {

    int total_count;
    boolean incomplete_results;

    static class CodeItem implements Serializable {
        // file name
        String name;
        String path;
        String sha;
        String url;
        String git_url;
        String html_url;
        double score;
        Repository repository;

        static class TextMatch implements Serializable {
            String object_url;
            String object_type;
            String property;
            String fragment;

            static class Match implements Serializable {
                String text;
                List<Integer> indices;
            }

            List<Match> matches;
        }

        List<TextMatch> text_matches;

    }

    List<CodeItem> items;

}
