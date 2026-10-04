#include "haze_shield_core.h"
#include <algorithm>
#include <cctype>
#include <mutex>

namespace haze {

namespace {
std::string ExtractHost(const std::string& url) {
    size_t start = url.find("://");
    if (start == std::string::npos) return "";
    start += 3;
    while (start < url.size() && url[start] == '/') ++start;
    size_t end = url.find_first_of("/?#", start);
    size_t port_pos = url.find(':', start);
    if (port_pos != std::string::npos && (end == std::string::npos || port_pos < end)) {
        end = port_pos;
    }
    std::string host = end == std::string::npos ? url.substr(start) : url.substr(start, end - start);
    size_t at = host.rfind('@');
    if (at != std::string::npos) host = host.substr(at + 1);
    std::transform(host.begin(), host.end(), host.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return host;
}

bool SameSite(const std::string& first, const std::string& second) {
    if (first.empty() || second.empty()) return false;
    auto root = [](const std::string& host) {
        size_t last = host.rfind('.');
        if (last == std::string::npos) return host;
        size_t previous = host.rfind('.', last - 1);
        return host.substr(previous == std::string::npos ? 0 : previous + 1);
    };
    return root(first) == root(second);
}
}  // namespace

void HazeShieldCore::AddAdDomain(const std::string& domain) {
    std::unique_lock lock(mutex_);
    ad_domains_.AddDomain(domain);
}

void HazeShieldCore::AddTrackerDomain(const std::string& domain) {
    std::unique_lock lock(mutex_);
    tracker_domains_.AddDomain(domain);
}

void HazeShieldCore::AddMalwareDomain(const std::string& domain) {
    std::unique_lock lock(mutex_);
    malware_domains_.AddDomain(domain);
}

void HazeShieldCore::AddPhishingDomain(const std::string& domain) {
    std::unique_lock lock(mutex_);
    phishing_domains_.AddDomain(domain);
}

void HazeShieldCore::AddPatternRule(const std::string& pattern, const std::string& category, bool is_exception) {
    std::unique_lock lock(mutex_);
    rule_matcher_.AddRule(pattern, category, is_exception);
}

void HazeShieldCore::ClearAllRules() {
    std::unique_lock lock(mutex_);
    ad_domains_.Clear();
    tracker_domains_.Clear();
    malware_domains_.Clear();
    phishing_domains_.Clear();
    rule_matcher_.Clear();
}

DecisionResult HazeShieldCore::EvaluateRequest(const std::string& request_url,
                                              const std::string& page_url,
                                              bool is_main_frame) const {
    const std::string request_host = ExtractHost(request_url);
    const std::string page_host = ExtractHost(page_url);
    RequestContext request{request_url, page_url, "OTHER", is_main_frame,
                           !request_host.empty() && !page_host.empty() &&
                               !SameSite(request_host, page_host)};
    return EvaluateRequest(request);
}

DecisionResult HazeShieldCore::EvaluateRequest(const RequestContext& request) const {
    std::shared_lock lock(mutex_);
    DecisionResult result = {false, "", "", "", 0L, 0L, request.resource_type,
                             request.is_third_party};
    const std::string& request_url = request.request_url;
    if (request_url.empty()) return result;

    std::string host = ExtractHost(request_url);

    // 1. Phishing Domain Check
    if (phishing_domains_.Matches(host)) {
        result.should_block = true;
        result.category = "PHISHING";
        result.reason = "Native C++ HazeShieldCore: Known phishing threat endpoint";
        result.bytes_saved = 64000L;
        result.time_saved_ms = 250L;
        return result;
    }

    // 2. Malware Domain Check
    if (malware_domains_.Matches(host)) {
        result.should_block = true;
        result.category = "MALICIOUS";
        result.reason = "Native C++ HazeShieldCore: Cryptojacking & malware distribution endpoint";
        result.bytes_saved = 120000L;
        result.time_saved_ms = 200L;
        return result;
    }

    // Main frame documents proceed unless flagged as malware/phishing above
    if (request.is_main_frame) return result;

    // 3. Pattern / Path Rules Check
    std::string cat, rule;
    if (rule_matcher_.Evaluate(request_url, &cat, &rule)) {
        result.should_block = true;
        result.category = cat;
        result.reason = "Native C++ HazeShieldCore: Pattern rule match";
        result.matched_rule = rule;
        result.bytes_saved = 32000L;
        result.time_saved_ms = 80L;
        return result;
    }

    // 4. Ad Domain Check
    if (ad_domains_.Matches(host)) {
        result.should_block = true;
        result.category = "ADS";
        result.reason = "Native C++ HazeShieldCore: Ad server / promotional network";
        result.bytes_saved = 48000L;
        result.time_saved_ms = 120L;
        return result;
    }

    // 5. Tracker Domain Check
    if (tracker_domains_.Matches(host)) {
        result.should_block = true;
        result.category = "TRACKERS";
        result.reason = "Native C++ HazeShieldCore: Behavioral analytics & cross-site tracker";
        result.bytes_saved = 24000L;
        result.time_saved_ms = 60L;
        return result;
    }

    return result;
}

}  // namespace haze
