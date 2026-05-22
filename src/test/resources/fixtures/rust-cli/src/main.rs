use clap::Parser;

#[derive(Parser)]
struct Args {
    pattern: String,
    file: String,
}

pub fn run(args: Args) -> std::io::Result<()> {
    println!("pattern={} file={}", args.pattern, args.file);
    Ok(())
}

fn main() {
    let args = Args::parse();
    run(args).unwrap();
}
