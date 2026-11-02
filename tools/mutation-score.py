"""Print the mutation score of a pitest mutations.xml (#66), the way pitest counts it.

    python3 -I tools/mutation-score.py splat-core/build/reports/pitest/mutations.xml

Killed means detected: KILLED, TIMED_OUT, MEMORY_ERROR. Test strength leaves out the mutants no test covers.
Last line: `mutation score: K/N (S%), test strength T%`. A missing or unreadable report exits 1.
"""
import collections
import sys
import xml.etree.ElementTree as ET


def score(path):
    root = ET.parse(path).getroot()
    if root.tag != "mutations":
        raise ValueError("not a pitest mutations report")
    status = collections.Counter(m.get("status") for m in root.findall("mutation"))
    total = sum(status.values())
    killed = status["KILLED"] + status["TIMED_OUT"] + status["MEMORY_ERROR"]
    covered = total - status["NO_COVERAGE"]
    return status, killed, total, covered


def main(argv):
    if len(argv) != 2:
        sys.exit("usage: mutation-score.py <mutations.xml>")
    try:
        status, killed, total, covered = score(argv[1])
    except (OSError, ET.ParseError, ValueError) as e:
        sys.exit(f"mutation score: unreadable report {argv[1]}: {e}")
    print("mutants: " + ", ".join(f"{k} {v}" for k, v in sorted(status.items())))
    print(
        f"mutation score: {killed}/{total} ({round(100 * killed / max(total, 1))}%), "
        f"test strength {round(100 * killed / max(covered, 1))}%"
    )


if __name__ == "__main__":
    main(sys.argv)
