use regex::Regex;

pub struct Matcher {
    re: Regex,
}

pub fn compile(pattern: &str) -> Matcher {
    Matcher { re: Regex::new(pattern).unwrap() }
}
