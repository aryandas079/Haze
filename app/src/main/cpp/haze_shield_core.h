#ifndef HAZE_SHIELD_CORE_H_
#define HAZE_SHIELD_CORE_H_

#include "domain_matcher.h"
#include "rule_matcher.h"
#include <shared_mutex>
#include <string>

namespace haze {

struct RequestContext {
    std::string request_url;
    std::string page_url;
    std::string resource_type;
    bool is_main_frame = false;
    bool is_third_party = false;
};

struct DecisionResult {
    bool should_block;
    std::string category;
    std::string reason;
    std::string matched_rule;
    long bytes_saved;
    long time_saved_ms;
    std::string resource_type;
    bool is_third_party;
};

class HazeShieldCore {
public:
    HazeShieldCore() = default;
    ~HazeShieldCore() = default;

    void AddAdDomain(const std::string& domain);
    void AddTrackerDomain(const std::string& domain);
    void AddMalwareDomain(const std::string& domain);
    void AddPhishingDomain(const std::string& domain);
    void AddPatternRule(const std::string& pattern, const std::string& category, bool is_exception);
    void ClearAllRules();

    DecisionResult EvaluateRequest(const std::string& request_url,
                                   const std::string& page_url,
                                   bool is_main_frame) const;
    DecisionResult EvaluateRequest(const RequestContext& request) const;

private:
    mutable std::shared_mutex mutex_;
    DomainMatcher ad_domains_;
    DomainMatcher tracker_domains_;
    DomainMatcher malware_domains_;
    DomainMatcher phishing_domains_;
    RuleMatcher rule_matcher_;
};

}  // namespace haze

#endif  // HAZE_SHIELD_CORE_H_
