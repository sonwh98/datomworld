/^\(deftest/ { slow = ($0 ~ /\^:slow/); name = $2 }
/every-vm=/ { if (!slow && name != "") print FILENAME ": " name }
