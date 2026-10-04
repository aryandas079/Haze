#ifndef HAZE_RULE_MATCHER_H_
#define HAZE_RULE_MATCHER_H_

#include <string>
#include <vector>

namespace haze {

struct FilterRule {
    std::string pattern;
    std::string category;
    bool is_exception;
};

class RuleMatcher {
public:
    RuleMatcher() = default;
    ~RuleMatcher() = default;

    void AddRule(const std::string& pattern, const std::string& category, bool is_exception);
    void Clear();
    bool Evaluate(const std::string& url, std::string* matched_category, std::string* matched_rule) const;
    size_t Size() const;

private:
    std::vector<FilterRule> rules_;
};

}  // namespace haze

#endif  // HAZE_RULE_MATCHER_H_
