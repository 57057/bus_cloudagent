"""Manual graph-search demo using the production retrieval tool."""
import argparse
from agent.tools.graph_tool import query_knowledge_graph


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query")
    args = parser.parse_args()
    print(query_knowledge_graph.invoke({"query": args.query}))


if __name__ == "__main__":
    main()
