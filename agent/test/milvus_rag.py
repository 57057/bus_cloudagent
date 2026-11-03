"""Manual vector-search demo using the production retrieval tool."""
import argparse
from agent.tools.vector_tool import query_vector_db


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query")
    args = parser.parse_args()
    print(query_vector_db.invoke({"query": args.query}))


if __name__ == "__main__":
    main()
