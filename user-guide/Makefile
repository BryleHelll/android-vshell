all: user_guide

user_guide: css/pandoc.css guide.md.in
	rm ../app/src/main/assets/guide.html
	pandoc --self-contained --css css/pandoc.css --from=markdown --to=html --output guide.html guide.md.in
	mv guide.html ../app/src/main/assets/guide.html
