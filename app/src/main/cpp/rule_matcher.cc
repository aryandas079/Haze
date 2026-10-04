#include "rule_matcher.h"
#include <algorithm>
#include <cctype>

namespace haze {

namespace {
std::string ToLower(const std::string& input) {
    std::string clean = input;
    std::transform(clean.begin(), clean.end(), clean.begin(), [](unsigned char c) {
        return std::tolower(c);
    });
    return clean;
}
}  // namespace

void RuleMatcher::AddRule(const std::string& pattern, const std::string& category, bool is_exception) {
    if (pattern.empty()) return;
    rules_.push_back({ToLower(pattern), category, is_exception});
}

void RuleMatcher::Clear() {
    rules_.clear();
}

size_t RuleMatcher::Size() const {
    return rules_.size();
}

bool RuleMatcher::Evaluate(const std::string& url, std::string* matched_category, std::string* matched_rule) const {
    if (url.empty() || rules_.empty()) return false;
    std::string lower_url = ToLower(url);

    // 1. Check Exceptions first (Allow rules)
    const FilterRule* best_exception = nullptr;
    const FilterRule* best_block = nullptr;
    for (const auto& rule : rules_) {
        if (lower_url.find(rule.pattern) == std::string::npos) continue;
        const FilterRule** candidate = rule.is_exception ? &best_exception : &best_block;
        if (*candidate == nullptr || rule.pattern.size() > (*candidate)->pattern.size()) {
            *candidate = &rule;
        }
    }
    if (best_exception != nullptr) {
            if (matched_category) *matched_category = "ALLOW";
            if (matched_rule) *matched_rule = "@@" + best_exception->pattern;
            return false;
    }
    if (best_block != nullptr) {
            if (matched_category) *matched_category = best_block->category;
            if (matched_rule) *matched_rule = best_block->pattern;
            return true; // Blocked
    }

    return false;
}

}  // namespace haze
