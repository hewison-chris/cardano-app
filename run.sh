# 1. Compile Elm frontend for production
cd frontend && npm run build && cd ..

# 2. Run backend loaded with .env
sbt "backend/run"
