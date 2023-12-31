package pmel.sdig.las

import javax.naming.directory.SearchResult
import java.util.regex.Matcher
import java.util.regex.Pattern

class SearchController {

    def search() {

        def searchJson = request.JSON;
        SearchRequest searchRequest = new SearchRequest(searchJson)
        def searchQuery = searchRequest.getQuery();

        def lookup = []

        // This matcher and regex grabs either blank separated words or groups of words in quotes.
        // https://stackoverflow.com/questions/3366281/tokenizing-a-string-but-ignoring-delimiters-within-quotes
        String regex = "\"([^\"]*)\"|(\\S+)";
        List<Dataset> datasetList = new ArrayList<>();
        Set results = []
        def offset
        def count = 1000
        if (searchRequest) {
            offset = searchRequest.getOffset();
            count = searchRequest.getCount();
            if (searchQuery) {
                Matcher m = Pattern.compile(regex).matcher(searchQuery);
                while (m.find()) {
                    if (m.group(1) != null) {
                        lookup.add("%" + m.group(1) + "%");
                    } else {
                        lookup.add("%" + m.group(2) + "%");
                    }
                }

                for (int i = 0; i < lookup.size(); i++) {
                    def termResults = Dataset.createCriteria().list {
                        or {
                            ilike("title", lookup.get(i))
                            ilike("history", lookup.get(i))
                        }
                        and {
                            eq("variableChildren", true)
                        }

                    }
                    results.addAll(termResults)
                }

                for (int i = 0; i < lookup.size(); i++) {
                    def termResults = Variable.createCriteria().list {
                        or {
                            ilike("title", lookup.get(i))
                            ilike("name", lookup.get(i))
                            ilike("geometry", lookup.get(i))
                            ilike("standard_name", lookup.get(i))
                        }
                    }
                    results.addAll(termResults)
                }
            }
            // Different part of the UI can send different properties to be searched
            // Each property is searched by either a quoted string which is an exact match, or keywords which should be search as "like"
            List<DatasetProperty> datasetProperties = searchRequest.getDatasetProperties();
            def exact = []
            def exact_name = []
            def like = []
            def like_name = []
            if (datasetProperties) {
                for (int i = 0; i < datasetProperties.size(); i++) {
                    DatasetProperty dp = datasetProperties.get(i)
                    Matcher m = Pattern.compile(regex).matcher(dp.getValue());
                    while (m.find()) {
                        if (m.group(1) != null) {
                            exact.add(m.group(1));
                            exact_name.add(dp.getName())
                        } else {
                            like.add("%" + m.group(2) + "%");
                            like_name.add(dp.getName())
                        }
                    }
                }
                // Search the exact
                for (int i = 0; i < exact.size(); i++) {
                    log.debug("Data set property: " + exact_name.get(i))
                    log.debug("      exact value: " + exact.get(i))
                    def dpResults = Dataset.createCriteria().list {
                        and {
                            eq(exact_name.get(i), exact.get(i))
                            eq("variableChildren", true)
                        }
                    }
                    log.debug("          found: " + dpResults.size())
                    results.addAll(dpResults)
                }

                // Search the "like"
                for (int i = 0; i < like.size(); i++) {
                    log.debug("Data set property: " + like_name.get(i))
                    log.debug("       like value: " + like.get(i))
                    def dpResults = Dataset.createCriteria().list {
                        and {
                            ilike(like_name.get(i), like.get(i))
                            eq("variableChildren", true)
                        }
                    }
                    log.debug("          found: " + dpResults.size())
                    results.addAll(dpResults)
                }
            }
            def vlike = []
            def vlike_name = []
            def vexact = []
            def vexact_name = []
            List<VariableProperty> variableProperties = searchRequest.getVariableProperties();
            if (variableProperties) {
                for (int i = 0; i < variableProperties.size(); i++) {
                    VariableProperty vp = variableProperties.get(i)
                    Matcher m = Pattern.compile(regex).matcher(vp.getValue());
                    while (m.find()) {
                        if (m.group(1) != null) {
                            vexact.add(m.group(1));
                            vexact_name.add(vp.getName())
                        } else {
                            vlike.add("%" + m.group(2) + "%");
                            vlike_name.add(vp.getName())
                        }
                    }
                    // Every exact match
                    for (int j = 0; j < vexact.size(); j++) {
                        log.debug("Data set property: " + vexact_name.get(i))
                        log.debug("      exact value: " + vexact.get(i))
                        def vpResults = Variable.createCriteria().list {
                            eq(vexact_name.get(i), vexact.get(i))
                        }
                        log.debug("          found: " + vpResults.size())
                        results.addAll(vpResults)
                    }
                    // All the "like" matches
                    for (int j = 0; j < vlike.size(); j++) {
                        log.debug("Data set property: " + vlike_name.get(i))
                        log.debug("       like value: " + vlike.get(i))
                        def vpResults = Variable.createCriteria().list {
                            ilike(vlike_name.get(i), vlike.get(i))
                        }
                        log.debug("          found: " + vpResults.size())
                        results.addAll(vpResults)
                    }
                }
            }

            Set datasetSet = []

            results.each { Object result ->
                if (result instanceof Dataset) {
                    Dataset d = (Dataset) result
                    if (d.id > 0) {
                        Dataset full = Dataset.get(d.id)
                        // In case the indexes contain dataset that are no longer defined
                        if (full) {
                            datasetSet.add(full)
                        }
                    }
                } else if (result instanceof Variable) {
                    Variable v = (Variable) result;
                    Dataset d = v.getDataset();
                    // Why a variable with no data set? Shouldn't happen.
                    if (d) {
                        if (d.id > 0) {
                            Dataset full = Dataset.get(d.id)
                            // In case the indexes contain dataset that are no longer defined
                            if (full) {
                                datasetSet.add(full)
                            }
                        }
                    } else {
                        log.debug("Variable with id=" + v.id + " has a null data set parent.")
                    }
                }
            }
            datasetList = new ArrayList(datasetSet);


            int start = offset;
            int end = datasetList.size();
            if (count < datasetList.size()) {
                end = offset + count;
                if (end > datasetList.size()) {
                    end = datasetList.size()
                }
            }

            log.debug(datasetList.size() + " search results found. Sending " + "start="+start + "  end="+end)
            SearchResults searchResults = new SearchResults();
            searchResults.setDatasetList(datasetList[start..<end])
            searchResults.setTotal(datasetList.size())
            searchResults.setStart(start)
            searchResults.setEnd(end)
            render(template: "search", model: [searchResults: searchResults])

        }
    }
}
